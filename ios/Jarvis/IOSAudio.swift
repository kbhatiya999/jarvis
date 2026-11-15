import AVFoundation
import JarvisCore

/// Mic (converted to 16 kHz mono PCM, with echo cancellation) and speaker (24 kHz PCM) for one session.
final class IOSAudio: NSObject, AudioIO {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 24000, channels: 1, interleaved: false)!
    private let micFormat = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: true)!
    private let lock = NSLock()
    private var scheduled: Int64 = 0
    private var played: Int64 = 0
    private var generation = 0
    private var capturing = false
    private var started = false

    override init() {
        super.init()
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker, .allowBluetooth])
        try? session.setActive(true)
        try? engine.inputNode.setVoiceProcessingEnabled(true) // echo cancellation: keeps Jarvis's voice out of the mic
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: outFormat)
        start()
    }

    private func start() {
        guard !started else { return }
        engine.prepare()
        started = (try? engine.start()) != nil
        if started { player.play() }
    }

    func startCapture(onChunk: @escaping (KotlinByteArray) -> Void) {
        guard !capturing else { return }
        capturing = true
        let input = engine.inputNode
        let hw = input.outputFormat(forBus: 0)
        guard hw.sampleRate > 0, let converter = AVAudioConverter(from: hw, to: micFormat) else { return }
        input.installTap(onBus: 0, bufferSize: 2048, format: hw) { [micFormat] buffer, _ in
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * 16000 / hw.sampleRate + 32)
            guard let out = AVAudioPCMBuffer(pcmFormat: micFormat, frameCapacity: capacity) else { return }
            var fed = false
            var error: NSError?
            converter.convert(to: out, error: &error) { _, status in
                if fed { status.pointee = .noDataNow; return nil }
                fed = true
                status.pointee = .haveData
                return buffer
            }
            guard out.frameLength > 0, let samples = out.int16ChannelData else { return }
            onChunk(IosBridge.shared.toBytes(data: Data(bytes: samples[0], count: Int(out.frameLength) * 2)))
        }
        start()
    }

    func stopCapture() {
        guard capturing else { return }
        engine.inputNode.removeTap(onBus: 0)
        capturing = false
    }

    func play(pcm: KotlinByteArray) {
        let data = IosBridge.shared.toData(bytes: pcm)
        let frames = data.count / 2
        guard frames > 0, let buffer = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: AVAudioFrameCount(frames)) else { return }
        buffer.frameLength = AVAudioFrameCount(frames)
        data.withUnsafeBytes { raw in
            let src = raw.bindMemory(to: Int16.self)
            let dst = buffer.floatChannelData![0]
            for i in 0..<frames { dst[i] = Float(src[i]) / 32768 }
        }
        lock.lock()
        scheduled += Int64(frames)
        let gen = generation
        lock.unlock()
        player.scheduleBuffer(buffer) { [weak self] in
            guard let self else { return }
            self.lock.lock()
            if gen == self.generation { self.played += Int64(frames) }
            self.lock.unlock()
        }
    }

    func flush() {
        lock.lock()
        generation += 1
        scheduled = 0
        played = 0
        lock.unlock()
        player.stop()
        player.play()
    }

    func remainingMs() -> Int64 {
        lock.lock()
        defer { lock.unlock() }
        return max(0, scheduled - played) * 1000 / 24000
    }

    func release() {
        stopCapture()
        player.stop()
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}
