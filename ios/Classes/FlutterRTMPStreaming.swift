import Flutter
import UIKit
import AVFoundation
import Accelerate
import CoreMotion
import HaishinKit
import os
import ReplayKit
import VideoToolbox
import Network

@objc
public class FlutterRTMPStreaming : NSObject {
    private var rtmpConnection = RTMPConnection()
    private var rtmpStream: RTMPStream!
    private var url: String? = nil
    private var name: String? = nil
    private var retries: Int = 0
    private var reconnectWorkItem: DispatchWorkItem?
    private var isClosed = false
    private var networkMonitor: NWPathMonitor?
    private let eventSink: FlutterEventSink
    private let myDelegate = MyRTMPStreamQoSDelagate()
    
    @objc
    public init(sink: @escaping FlutterEventSink) {
        eventSink = sink
    }
    
    @objc
    public func open(url: String, width: Int, height: Int, bitrate: Int) {
        isClosed = false
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self, !self.isClosed, path.status == .unsatisfied else { return }
            self.retryConnection("Network unavailable")
            self.rtmpConnection.close()
        }
        networkMonitor = monitor
        monitor.start(queue: .main)
        rtmpStream = RTMPStream(connection: rtmpConnection)
        rtmpStream.captureSettings = [
            .sessionPreset: AVCaptureSession.Preset.hd1280x720,
            .continuousAutofocus: true,
            .continuousExposure: true
        ]
        rtmpConnection.addEventListener(.rtmpStatus, selector:#selector(rtmpStatusHandler), observer: self)
        rtmpConnection.addEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)
        rtmpStream.addEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        
        let uri = URL(string: url)
        self.name = uri?.pathComponents.last
        var bits = url.components(separatedBy: "/")
        bits.removeLast()
        self.url = bits.joined(separator: "/")
        
        // TODO: Da correggere
        rtmpStream.videoSettings = [
            .width: width,
            .height: height,
            .profileLevel: kVTProfileLevel_H264_Baseline_AutoLevel,
            .maxKeyFrameIntervalDuration: 2,
            .bitrate: bitrate
        ]
        rtmpStream.captureSettings = [
            .fps: 30
        ]
        rtmpStream.delegate = myDelegate
        self.retries = 0
        // Run this on the ui thread.
        DispatchQueue.main.async {
            if let orientation = DeviceUtil.videoOrientation(by:  UIApplication.shared.statusBarOrientation) {
                self.rtmpStream.orientation = orientation
                print(String(format:"Orient %d", orientation.rawValue))
                switch (orientation) {
                case .landscapeLeft, .landscapeRight:
                    self.rtmpStream.videoSettings[.width] = width;
                    self.rtmpStream.videoSettings[.height] = height;
                    break;
                default:
                    break;
                }
            }
            self.rtmpConnection.connect(self.url ?? "frog")
        }
    }
    
    @objc
    private func rtmpStatusHandler(_ notification: Notification) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.rtmpStatusHandler(notification) }
            return
        }
        guard !isClosed else { return }
        let e = Event.from(notification)
        guard let data: ASObject = e.data as? ASObject, let code: String = data["code"] as? String else {
            return
        }
        print(e)
        
        switch code {
        case RTMPConnection.Code.connectSuccess.rawValue:
            reconnectWorkItem?.cancel()
            reconnectWorkItem = nil
            rtmpStream.publish(name)
            break
        case RTMPStream.Code.publishStart.rawValue:
            retries = 0
            eventSink(["event": "rtmp_connected"])
            break
        case RTMPConnection.Code.connectFailed.rawValue, RTMPConnection.Code.connectClosed.rawValue:
            retryConnection("connection failed " + code)
            break
        case RTMPConnection.Code.connectRejected.rawValue, RTMPStream.Code.publishBadName.rawValue, RTMPStream.Code.failed.rawValue:
            eventSink(["event": "error", "errorDescription": code])
            break
        default:
            break
        }
    }
    
    @objc
    private func rtmpErrorHandler(_ notification: Notification) {
        if #available(iOS 10.0, *) {
            os_log("%s", notification.name.rawValue)
        }
        retryConnection("rtmp disconnected")
    }

    private func retryConnection(_ reason: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self, !self.isClosed, self.reconnectWorkItem == nil else { return }
            guard self.retries < 3, let url = self.url else {
                self.eventSink(["event": "rtmp_stopped", "errorDescription": reason])
                return
            }
            self.retries += 1
            self.eventSink(["event": "rtmp_retry", "errorDescription": reason])
            let retry = DispatchWorkItem { [weak self] in
                guard let self = self, !self.isClosed else { return }
                self.reconnectWorkItem = nil
                self.rtmpConnection.connect(url)
            }
            self.reconnectWorkItem = retry
            DispatchQueue.main.asyncAfter(deadline: .now() + pow(2.0, Double(self.retries)), execute: retry)
        }
    }
    
    @objc
    public func pauseVideoStreaming() {
        rtmpStream.paused = true
    }
    
    @objc
    public func resumeVideoStreaming() {
        rtmpStream.paused = false
    }
    
    @objc
    public func isPaused() -> Bool{
        return rtmpStream.paused
    }
    
    
    @objc
    public func getStreamStatistics() -> NSDictionary {
        let ret: NSDictionary = [
            "paused": isPaused(),
            "bitrate": rtmpStream.videoSettings[.bitrate]!,
            "width": rtmpStream.videoSettings[.width]!,
            "height": rtmpStream.videoSettings[.height]!,
            "fps": (rtmpStream.captureSettings[.fps]! as! NSNumber).floatValue,
            "orientation": rtmpStream.orientation.rawValue
        ]
        //ret["cacheSize"] = rtmpConnection.bandWidth
        //ret["sentAudioFrames"] = rtmpCamera!!.sentAudioFrames
        //        ret["sentVideoFrames"] = rtmpCamera!!.sentVideoFrames
        //if (rtmpCamera!!.droppedAudioFrames == null) {
        //ret["droppedAudioFrames"] = 0
        //} else {
        //ret["droppedAudioFrames"] = rtmpCamera!!.droppedAudioFrames
        //}
        //ret["droppedVideoFrames"] = rtmpCamera!!.droppedVideoFrames
        //ret["isAudioMuted"] = rtmpCamera!!.isAudioMuted
        return ret
    }
    
    @objc
    public func addVideoData(buffer: CMSampleBuffer) {
        if let description = CMSampleBufferGetFormatDescription(buffer) {
            let dimensions = CMVideoFormatDescriptionGetDimensions(description)
            rtmpStream.videoSettings = [
                .width: dimensions.width,
                .height: dimensions.height,
                .profileLevel: kVTProfileLevel_H264_Baseline_AutoLevel,
                .maxKeyFrameIntervalDuration: 2,
                .bitrate: 1200 * 1024
            ]
            rtmpStream.captureSettings = [
                .fps: 24
            ]
        }
        rtmpStream.appendSampleBuffer( buffer, withType: .video)
    }
    
    @objc
    public func addAudioData(buffer: CMSampleBuffer) {
        rtmpStream.appendSampleBuffer( buffer, withType: .audio)
    }
    
    @objc
    public func close() {
        isClosed = true
        networkMonitor?.cancel()
        networkMonitor = nil
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        rtmpConnection.removeEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        rtmpConnection.removeEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)
        rtmpStream?.removeEventListener(.rtmpStatus, selector: #selector(rtmpStatusHandler), observer: self)
        rtmpConnection.close()
    }
}


class MyRTMPStreamQoSDelagate: RTMPStreamDelegate {
    let minBitrate: UInt32 = 300 * 1024
    let maxBitrate: UInt32 = 2500 * 1024
    let incrementBitrate: UInt32 = 512 * 1024
    
    func didPublishSufficientBW(_ stream: RTMPStream, withConnection: RTMPConnection) {
        guard let videoBitrate = stream.videoSettings[.bitrate] as? UInt32 else { return }
        
        var newVideoBitrate = videoBitrate + incrementBitrate
        if newVideoBitrate > maxBitrate {
            newVideoBitrate = maxBitrate
        }
        print("didPublishSufficientBW update: \(videoBitrate) -> \(newVideoBitrate)")
        stream.videoSettings[.bitrate] = newVideoBitrate
    }
    
    
    // detect upload insufficent BandWidth
    func didPublishInsufficientBW(_ stream:RTMPStream, withConnection:RTMPConnection) {
        guard let videoBitrate = stream.videoSettings[.bitrate] as? UInt32 else { return }
        
        var         newVideoBitrate = UInt32(videoBitrate / 2)
        if newVideoBitrate < minBitrate {
            newVideoBitrate = minBitrate
        }
        print("didPublishInsufficientBW update: \(videoBitrate) -> \(newVideoBitrate)")
        stream.videoSettings[.bitrate] = newVideoBitrate
    }
    
    func clear() {
    }
}
