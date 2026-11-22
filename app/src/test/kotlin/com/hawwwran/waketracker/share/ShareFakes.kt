package com.hawwwran.waketracker.share

class MapKeyValues : KeyValues {
    private val strings = HashMap<String, String?>()
    private val longs = HashMap<String, Long>()

    override fun string(key: String) = strings[key]
    override fun long(key: String) = longs[key] ?: 0L
    override fun put(key: String, value: String?) {
        strings[key] = value
    }

    override fun put(key: String, value: Long) {
        longs[key] = value
    }
}

/** server/api.php in memory: the same rules about tokens, versions and unknown devices. */
class FakeRelay : Relay {
    class Device(val writeToken: String, val readToken: String) {
        var version = 0L
        val blobs = HashMap<String, Pair<Long, ByteArray>>()
    }

    val devices = HashMap<String, Device>()
    var reachable = true
    var puts = 0

    /** Runs as a file sets off for the relay, as a hook for "meanwhile": the phone records more, the connection drops. */
    var beforePut: (name: String) -> Unit = {}

    private fun online() {
        if (!reachable) throw RelayException(RelayException.Reason.UNREACHABLE)
    }

    private fun device(deviceId: String) = devices[deviceId] ?: throw RelayException(RelayException.Reason.UNKNOWN_DEVICE)

    override fun hello() = online()

    override fun register(deviceId: String, writeToken: String, readToken: String) {
        online()
        val known = devices[deviceId]
        if (known == null) {
            devices[deviceId] = Device(writeToken, readToken)
        } else if (known.writeToken != writeToken || known.readToken != readToken) {
            throw RelayException(RelayException.Reason.REFUSED)
        }
    }

    override fun put(deviceId: String, name: String, writeToken: String, content: ByteArray) {
        beforePut(name)
        online()
        val device = device(deviceId)
        if (device.writeToken != writeToken) throw RelayException(RelayException.Reason.REFUSED)
        puts++
        device.version++
        device.blobs[name] = device.version to content
    }

    override fun changes(deviceId: String, readToken: String, since: Long): Changes {
        online()
        val device = device(deviceId)
        if (device.readToken != readToken) throw RelayException(RelayException.Reason.REFUSED)
        val changed = device.blobs.filter { it.value.first > since }.map { Change(it.key, it.value.first) }.sortedBy { it.version }
        return Changes(device.version, changed)
    }

    override fun get(deviceId: String, name: String, readToken: String): ByteArray {
        online()
        val device = device(deviceId)
        if (device.readToken != readToken) throw RelayException(RelayException.Reason.REFUSED)
        return device.blobs[name]?.second ?: throw RelayException(RelayException.Reason.FAILED)
    }

    override fun forget(deviceId: String, writeToken: String) {
        online()
        if (device(deviceId).writeToken != writeToken) throw RelayException(RelayException.Reason.REFUSED)
        devices.remove(deviceId)
    }
}
