<?php
/**
 * Wake Tracker relay.
 *
 * A phone that shares its record uploads it here, and the phones it was paired with fetch
 * it from here. Everything a phone uploads is encrypted on the phone, with a key that
 * travels only in the pairing code from screen to camera. This script stores and returns
 * opaque files; it has no key and never sees a time, a day's content or a phone's name.
 *
 * What it does know: that a device with some random id exists, the names of its files (one
 * per day, as a day number, plus "meta"), their sizes (padded on the phone to whole
 * blocks) and when they were written.
 *
 * Access is by two tokens per device, both random and chosen by the phone. The write token
 * never leaves that phone. The read token is derived from the secret in the pairing code,
 * so exactly the paired phones have it. Only hashes of the tokens are stored.
 *
 *   GET    ?a=hello                          is this a relay, and which protocol
 *   POST   ?a=register&device=ID             {"write": token, "read": token}
 *   PUT    ?a=blob&device=ID&name=NAME       body: the file           (write token)
 *   GET    ?a=changes&device=ID&since=N      files written after N    (read token)
 *   GET    ?a=blob&device=ID&name=NAME       the file                 (read token)
 *   DELETE ?a=device&device=ID               forget the device        (write token)
 *
 * Tokens go in "Authorization: Bearer". Errors are {"error": code} with a fitting status.
 * Files live in data/, which .htaccess closes to the web.
 */

declare(strict_types=1);

const API_VERSION = 1;
const DATA_DIR = __DIR__ . '/data';

// Registration is open to whoever knows the address, so these bound what a stranger could
// make the host store: MAX_DEVICES * MAX_DEVICE_BYTES at the very worst.
const MAX_DEVICES = 50;
const MAX_DEVICE_BYTES = 16 * 1024 * 1024;
const MAX_BLOB_BYTES = 256 * 1024;
const MAX_BLOBS = 6000;

const DEVICE_PATTERN = '/^[A-Za-z0-9_-]{22}$/';
const NAME_PATTERN = '/^(meta|d[0-9]{1,6})$/';
const TOKEN_PATTERN = '/^[A-Za-z0-9_-]{43}$/';

function respond(int $status, array $body): never
{
    http_response_code($status);
    header('Content-Type: application/json');
    header('Cache-Control: no-store');
    echo json_encode($body);
    exit;
}

function fail(int $status, string $code): never
{
    respond($status, ['error' => $code]);
}

function param(string $key, string $pattern): string
{
    $value = $_GET[$key] ?? '';
    if (!is_string($value) || preg_match($pattern, $value) !== 1) {
        fail(400, "bad_$key");
    }
    return $value;
}

function bearer(): string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    return str_starts_with($header, 'Bearer ') ? substr($header, 7) : '';
}

function deviceDir(string $device): string
{
    return DATA_DIR . '/' . $device;
}

/** Runs $work with the device's files to itself (or shared with other readers). */
function locked(string $device, bool $writing, callable $work): mixed
{
    $handle = fopen(deviceDir($device) . '/lock', 'c');
    if ($handle === false || !flock($handle, $writing ? LOCK_EX : LOCK_SH)) {
        fail(500, 'storage');
    }
    try {
        return $work();
    } finally {
        flock($handle, LOCK_UN);
        fclose($handle);
    }
}

function readJson(string $path): array
{
    $text = @file_get_contents($path);
    $value = $text === false ? null : json_decode($text, true);
    return is_array($value) ? $value : [];
}

/** Written beside the target and renamed over it, so a reader never finds half a file. */
function writeFile(string $path, string $content): void
{
    $partial = $path . '.' . bin2hex(random_bytes(6)) . '.part';
    if (file_put_contents($partial, $content) !== strlen($content) || !rename($partial, $path)) {
        @unlink($partial);
        fail(500, 'storage');
    }
}

function removeDevice(string $device): void
{
    $dir = deviceDir($device);
    foreach (scandir($dir) ?: [] as $entry) {
        if ($entry !== '.' && $entry !== '..') {
            @unlink("$dir/$entry");
        }
    }
    @rmdir($dir);
}

/** Stops unless the device exists and the request carries its $kind ("write" or "read") token. */
function authorise(string $device, string $kind): void
{
    $auth = readJson(deviceDir($device) . '/auth.json');
    if (!isset($auth[$kind])) {
        fail(404, 'unknown_device');
    }
    if (!hash_equals($auth[$kind], hash('sha256', bearer()))) {
        fail(403, 'wrong_token');
    }
}

function ensureDataDir(): void
{
    if (!is_dir(DATA_DIR) && !mkdir(DATA_DIR, 0770) && !is_dir(DATA_DIR)) {
        fail(500, 'storage');
    }
    // Apache reads this; the files are ciphertext and token hashes either way.
    if (!is_file(DATA_DIR . '/.htaccess')) {
        file_put_contents(DATA_DIR . '/.htaccess', "Require all denied\n");
    }
}

function register(string $device): never
{
    $body = json_decode(file_get_contents('php://input', false, null, 0, 4096) ?: '', true);
    $write = is_array($body) ? ($body['write'] ?? '') : '';
    $read = is_array($body) ? ($body['read'] ?? '') : '';
    if (!is_string($write) || !is_string($read)
        || preg_match(TOKEN_PATTERN, $write) !== 1 || preg_match(TOKEN_PATTERN, $read) !== 1) {
        fail(400, 'bad_tokens');
    }
    $hashes = ['write' => hash('sha256', $write), 'read' => hash('sha256', $read)];

    ensureDataDir();
    $dir = deviceDir($device);
    if (is_dir($dir)) {
        // Registering again with the same tokens is how a phone makes sure it is known here.
        $auth = readJson("$dir/auth.json");
        $same = hash_equals($auth['write'] ?? '', $hashes['write']) && hash_equals($auth['read'] ?? '', $hashes['read']);
        $same ? respond(200, ['registered' => true]) : fail(409, 'device_taken');
    }
    $devices = count(array_filter(scandir(DATA_DIR) ?: [], fn ($entry) => preg_match(DEVICE_PATTERN, $entry) === 1));
    if ($devices >= MAX_DEVICES) {
        fail(507, 'relay_full');
    }
    if (!mkdir($dir, 0770)) {
        fail(500, 'storage');
    }
    writeFile("$dir/auth.json", json_encode($hashes + ['created' => time()]));
    writeFile("$dir/index.json", json_encode(['version' => 0, 'blobs' => new stdClass()]));
    respond(200, ['registered' => true]);
}

function putBlob(string $device, string $name): never
{
    authorise($device, 'write');
    $content = file_get_contents('php://input', false, null, 0, MAX_BLOB_BYTES + 1);
    if ($content === false || $content === '') {
        fail(400, 'empty');
    }
    if (strlen($content) > MAX_BLOB_BYTES) {
        fail(413, 'too_large');
    }
    $version = locked($device, true, function () use ($device, $name, $content) {
        $dir = deviceDir($device);
        $index = readJson("$dir/index.json");
        $blobs = $index['blobs'] ?? [];
        $others = 0;
        foreach ($blobs as $known => $entry) {
            if ($known !== $name) {
                $others += $entry['size'];
            }
        }
        if ($others + strlen($content) > MAX_DEVICE_BYTES || (!isset($blobs[$name]) && count($blobs) >= MAX_BLOBS)) {
            fail(507, 'device_full');
        }
        $version = ($index['version'] ?? 0) + 1;
        // The file first: an index that lists a version must find that content.
        writeFile("$dir/$name.bin", $content);
        $blobs[$name] = ['version' => $version, 'size' => strlen($content)];
        writeFile("$dir/index.json", json_encode(['version' => $version, 'blobs' => $blobs]));
        return $version;
    });
    respond(200, ['version' => $version]);
}

function changes(string $device): never
{
    authorise($device, 'read');
    $since = (int) param('since', '/^[0-9]{1,12}$/');
    $index = locked($device, false, fn () => readJson(deviceDir($device) . '/index.json'));
    $changed = [];
    foreach ($index['blobs'] ?? [] as $name => $entry) {
        if ($entry['version'] > $since) {
            $changed[] = ['name' => $name, 'version' => $entry['version'], 'size' => $entry['size']];
        }
    }
    usort($changed, fn ($a, $b) => $a['version'] <=> $b['version']);
    respond(200, ['version' => $index['version'] ?? 0, 'blobs' => $changed]);
}

function getBlob(string $device, string $name): never
{
    authorise($device, 'read');
    $content = locked($device, false, fn () => @file_get_contents(deviceDir($device) . "/$name.bin"));
    if ($content === false) {
        fail(404, 'unknown_blob');
    }
    header('Content-Type: application/octet-stream');
    header('Content-Length: ' . strlen($content));
    header('Cache-Control: no-store');
    echo $content;
    exit;
}

function deleteDevice(string $device): never
{
    authorise($device, 'write');
    locked($device, true, fn () => null);
    removeDevice($device);
    respond(200, ['deleted' => true]);
}

$action = $_GET['a'] ?? '';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$route = "$method $action";

if ($route === 'GET hello') {
    // The release this is, as VERSION.md says it; the deploy script reads it back from here.
    preg_match('/^version: *(\S+)/m', (string) @file_get_contents(__DIR__ . '/VERSION.md'), $found);
    respond(200, ['service' => 'wake-tracker', 'api' => API_VERSION, 'version' => $found[1] ?? 'unknown']);
}
$device = param('device', DEVICE_PATTERN);
match ($route) {
    'POST register' => register($device),
    'PUT blob' => putBlob($device, param('name', NAME_PATTERN)),
    'GET changes' => changes($device),
    'GET blob' => getBlob($device, param('name', NAME_PATTERN)),
    'DELETE device' => deleteDevice($device),
    default => fail(404, 'unknown_action'),
};
