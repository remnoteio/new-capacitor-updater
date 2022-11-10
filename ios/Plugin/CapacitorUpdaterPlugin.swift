import Foundation
import Capacitor
import Version

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(CapacitorUpdaterPlugin)
public class CapacitorUpdaterPlugin: CAPPlugin {
  private var implementation = CapacitorUpdater()
  static let updateUrlDefault = "https://api.capgo.app/updates"
  static let statsUrlDefault = "https://api.capgo.app/stats"
  static let channelUrlDefault = "https://api.capgo.app/channel_self"
  let DELAY_CONDITION_PREFERENCES = ""
  private var updateUrl = ""
  private var statsUrl = ""
  private var currentVersionNative: Version = "0.0.0"
  private var autoUpdate = false
  private var appReadyTimeout = 10000
  private var appReadyCheck: DispatchWorkItem?
  private var resetWhenUpdate = true
  private var autoDeleteFailed = false
  private var autoDeletePrevious = false
  private var backgroundWork: DispatchWorkItem?
  private var taskRunning = false
  
  override public func load() {
    let allowEmulatorProd = getConfig().getBoolean("allowEmulatorProd", true)
    if (!allowEmulatorProd && self.isEmulator() && (self.isAppStoreReceiptSandbox() || self.hasEmbeddedMobileProvision())) {
      return
    }
    print("\(self.implementation.TAG) init for device \(self.implementation.deviceID)")
    do {
      currentVersionNative = try Version(Bundle.main.versionName ?? "0.0.0")
    } catch {
      print("\(self.implementation.TAG) Cannot get version native \(currentVersionNative)")
    }
    autoDeleteFailed = getConfig().getBoolean("autoDeleteFailed", true)
    autoDeletePrevious = getConfig().getBoolean("autoDeletePrevious", true)
    updateUrl = getConfig().getString("updateUrl", CapacitorUpdaterPlugin.updateUrlDefault)!
    autoUpdate = getConfig().getBoolean("autoUpdate", true)
    appReadyTimeout = getConfig().getInt("appReadyTimeout", 10000)
    resetWhenUpdate = getConfig().getBoolean("resetWhenUpdate", true)
    
    implementation.appId = Bundle.main.bundleIdentifier ?? ""
    implementation.notifyDownload = notifyDownload
    let config = (self.bridge?.viewController as? CAPBridgeViewController)?.instanceDescriptor().legacyConfig
    if config?["appId"] != nil {
      implementation.appId = config?["appId"] as! String
    }
    implementation.statsUrl = getConfig().getString("statsUrl", CapacitorUpdaterPlugin.statsUrlDefault)!
    implementation.channelUrl = getConfig().getString("channelUrl", CapacitorUpdaterPlugin.channelUrlDefault)!
    if resetWhenUpdate {
      self.cleanupObsoleteVersions()
    }
    let nc = NotificationCenter.default
    nc.addObserver(self, selector: #selector(appMovedToBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
    nc.addObserver(self, selector: #selector(appMovedToForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
    nc.addObserver(self, selector: #selector(appKilled), name: UIApplication.willTerminateNotification, object: nil)
    self.appMovedToForeground()
  }
  
  // MARK: Private
  private func hasEmbeddedMobileProvision() -> Bool {
    guard Bundle.main.path(forResource: "embedded", ofType: "mobileprovision") == nil else {
      return true
    }
    return false
  }
  
  private func isAppStoreReceiptSandbox() -> Bool {
    
    if isEmulator() {
      return false
    } else {
      guard let url = Bundle.main.appStoreReceiptURL else {
        return false
      }
      guard url.lastPathComponent == "sandboxReceipt" else {
        return false
      }
      return true
    }
  }
  
  private func isEmulator() -> Bool {
#if targetEnvironment(simulator)
    return true
#else
    return false
#endif
  }
  
  private func cleanupObsoleteVersions() {
    var LatestVersionNative: Version = "0.0.0"
    do {
      LatestVersionNative = try Version(UserDefaults.standard.string(forKey: "LatestVersionNative") ?? "0.0.0")
    } catch {
      print("\(self.implementation.TAG) Cannot get version native \(currentVersionNative)")
    }
    if LatestVersionNative != "0.0.0" && currentVersionNative.major > LatestVersionNative.major {
      _ = self._reset(toLastSuccessful: false)
      let res = implementation.list()
      res.forEach { version in
        print("\(self.implementation.TAG) Deleting obsolete bundle: \(version)")
        let res = implementation.delete(id: version.getId())
        if !res {
          print("\(self.implementation.TAG) Delete failed, id \(version.getId()) doesn't exist")
        }
      }
    }
    UserDefaults.standard.set( self.currentVersionNative.description, forKey: "LatestVersionNative")
    UserDefaults.standard.synchronize()
  }
  
  @objc func notifyDownload(id: String, percent: Int) {
    let bundle = self.implementation.getBundleInfo(id: id)
    self.notifyListeners("download", data: ["percent": percent, "bundle": bundle.toJSON()])
    if percent == 100 {
      self.notifyListeners("downloadComplete", data: ["bundle": bundle.toJSON()])
    }
  }
  
  @objc func getDeviceId(_ call: CAPPluginCall) {
    call.resolve(["deviceId": implementation.deviceID])
  }
  
  @objc func getPluginVersion(_ call: CAPPluginCall) {
    call.resolve(["version": implementation.pluginVersion])
  }
  
  @objc func download(_ call: CAPPluginCall) {
    guard let urlString = call.getString("url") else {
      print("\(self.implementation.TAG) Download called without url")
      call.reject("Download called without url")
      return
    }
    guard let version = call.getString("version") else {
      print("\(self.implementation.TAG) Download called without version")
      call.reject("Download called without version")
      return
    }
    let url = URL(string: urlString)
    print("\(self.implementation.TAG) Downloading \(url!)")
    let future = self.implementation.download(url: url!, version: version)
    future.observe { res in
      switch res {
      case .success(let info):
        call.resolve(info.toJSON())
      case .failure(let error):
        print("\(self.implementation.TAG) Failed to download from: \(url!) \(error.localizedDescription)")
        self.notifyListeners("downloadFailed", data: ["version": version])
        let current: BundleInfo = self.implementation.getCurrentBundle()
        self.implementation.sendStats(action: "download_fail", versionName: current.getVersionName())
        call.reject("Failed to download from: \(url!)", error.localizedDescription)
      }}
    
  }
  
  private func _reload() -> Bool {
    guard let bridge = self.bridge else { return false }
    let id = self.implementation.getCurrentBundleId()
    let destHot = self.implementation.getPathHot(id: id)
    print("\(self.implementation.TAG) Reloading \(id)")
    if let vc = bridge.viewController as? CAPBridgeViewController {
      vc.setServerBasePath(path: destHot.path)
      self.checkAppReady()
      self.notifyListeners("appReloaded", data: [:])
      return true
    }
    return false
  }
  
  @objc func reload(_ call: CAPPluginCall) {
    if self._reload() {
      call.resolve()
    } else {
      print("\(self.implementation.TAG) Reload failed")
      call.reject("Reload failed")
    }
  }
  
  @objc func next(_ call: CAPPluginCall) {
    guard let id = call.getString("id") else {
      print("\(self.implementation.TAG) Next called without id")
      call.reject("Next called without id")
      return
    }
    print("\(self.implementation.TAG) Setting next active id \(id)")
    if !self.implementation.setNextBundle(next: id) {
      print("\(self.implementation.TAG) Set next version failed. id \(id) does not exist.")
      call.reject("Set next version failed. id \(id) does not exist.")
    } else {
      call.resolve(self.implementation.getBundleInfo(id: id).toJSON())
    }
  }
  
  @objc func set(_ call: CAPPluginCall) {
    guard let id = call.getString("id") else {
      print("\(self.implementation.TAG) Set called without id")
      call.reject("Set called without id")
      return
    }
    let res = implementation.set(id: id)
    print("\(self.implementation.TAG) Set active bundle: \(id)")
    if !res {
      print("\(self.implementation.TAG) Bundle successfully set to: \(id) ")
      call.reject("Update failed, id \(id) doesn't exist")
    } else {
      self.reload(call)
    }
  }
  
  @objc func delete(_ call: CAPPluginCall) {
    guard let id = call.getString("id") else {
      print("\(self.implementation.TAG) Delete called without version")
      call.reject("Delete called without id")
      return
    }
    let res = implementation.delete(id: id)
    if res {
      call.resolve()
    } else {
      print("\(self.implementation.TAG) Delete failed, id \(id) doesn't exist")
      call.reject("Delete failed, id \(id) doesn't exist")
    }
  }
  
  @objc func list(_ call: CAPPluginCall) {
    let res = implementation.list()
    var resArr: [[String: String]] = []
    for v in res {
      resArr.append(v.toJSON())
    }
    call.resolve([
      "bundles": resArr
    ])
  }
  
  @objc func getLatest(_ call: CAPPluginCall) {
    DispatchQueue.global(qos: .background).async {
      let res = self.implementation.getLatest(url: URL(string: self.updateUrl)!)
      call.resolve(res.toDict())
    }
  }
  
  @objc func setChannel(_ call: CAPPluginCall) {
    guard let channel = call.getString("channel") else {
      print("\(self.implementation.TAG) setChannel called without channel")
      call.reject("setChannel called without channel")
      return
    }
    DispatchQueue.global(qos: .background).async {
      guard let res = self.implementation.setChannel(channel: channel) else {
        call.reject("Cannot setChannel")
        return
      }
      call.resolve(res.toDict())
    }
  }
  
  @objc func getChannel(_ call: CAPPluginCall) {
    DispatchQueue.global(qos: .background).async {
      guard let res = self.implementation.getChannel() else {
        call.reject("Cannot getChannel")
        return
      }
      call.resolve(res.toDict())
    }
  }
  @objc func setCustomId(_ call: CAPPluginCall) {
    guard let customId = call.getString("customId") else {
      print("\(self.implementation.TAG) setCustomId called without customId")
      call.reject("setCustomId called without customId")
      return
    }
    self.implementation.customId = customId
  }
  
  @objc func _reset(toLastSuccessful: Bool) -> Bool {
    guard let bridge = self.bridge else { return false }
    
    if let vc = bridge.viewController as? CAPBridgeViewController {
      let fallback: BundleInfo = self.implementation.getFallbackBundle()
      if toLastSuccessful && !fallback.isBuiltin() {
        print("\(self.implementation.TAG) Resetting to: \(fallback.toString())")
        return self.implementation.set(bundle: fallback) && self._reload()
      }
      self.implementation.reset()
      vc.setServerBasePath(path: "")
      DispatchQueue.main.async {
        vc.loadView()
        vc.viewDidLoad()
        print("\(self.implementation.TAG) Reset to builtin version")
      }
      return true
    }
    return false
  }
  
  @objc func reset(_ call: CAPPluginCall) {
    let toLastSuccessful = call.getBool("toLastSuccessful") ?? false
    if self._reset(toLastSuccessful: toLastSuccessful) {
      return call.resolve()
    }
    print("\(self.implementation.TAG) Reset failed")
    call.reject("\(self.implementation.TAG) Reset failed")
  }
  
  @objc func current(_ call: CAPPluginCall) {
    let bundle: BundleInfo = self.implementation.getCurrentBundle()
    call.resolve([
      "bundle": bundle.toJSON(),
      "native": self.currentVersionNative.description
    ])
  }
  
  @objc func notifyAppReady(_ call: CAPPluginCall) {
    let version = self.implementation.getCurrentBundle()
    self.implementation.setSuccess(bundle: version, autoDeletePrevious: self.autoDeletePrevious)
    print("\(self.implementation.TAG) Current bundle loaded successfully. ['notifyAppReady()' was called] \(version.toString())")
    call.resolve()
  }
  
  @objc func setMultiDelay(_ call: CAPPluginCall) {
    guard let delayConditionList = call.getValue("delayConditions") else {
      print("\(self.implementation.TAG) setMultiDelay called without delayCondition")
      call.reject("setMultiDelay called without delayCondition")
      return
    }
    let delayConditions: String = toJson(object: delayConditionList)
    if _setMultiDelay(delayConditions: delayConditions) {
      call.resolve()
    } else {
      call.reject("Failed to delay update")
    }
  }
  
  @available(*, deprecated, message: "use SetMultiDelay instead")
  @objc func setDelay(_ call: CAPPluginCall) {
    let kind: String = call.getString("kind", "")
    let value: String? = call.getString("value", "")
    let delayConditions: String = "[{\"kind\":\"\(kind)\", \"value\":\"\(value ?? "")\"}]"
    if _setMultiDelay(delayConditions: delayConditions) {
      call.resolve()
    } else {
      call.reject("Failed to delay update")
    }
  }
  
  private func _setMultiDelay(delayConditions: String?) -> Bool {
    if delayConditions != nil && "" != delayConditions {
      UserDefaults.standard.set(delayConditions, forKey: DELAY_CONDITION_PREFERENCES)
      UserDefaults.standard.synchronize()
      print("\(self.implementation.TAG) Delay update saved.")
      return true
    } else {
      print("\(self.implementation.TAG) Failed to delay update, [Error calling '_setMultiDelay()']")
      return false
    }
  }
  
  private func _cancelDelay(source: String) {
    print("\(self.implementation.TAG) delay Canceled from \(source)")
    UserDefaults.standard.removeObject(forKey: DELAY_CONDITION_PREFERENCES)
    UserDefaults.standard.synchronize()
  }
  
  @objc func cancelDelay(_ call: CAPPluginCall) {
    self._cancelDelay(source: "JS")
    call.resolve()
  }
  
  private func _checkCancelDelay(killed: Bool) {
    let delayUpdatePreferences = UserDefaults.standard.string(forKey: DELAY_CONDITION_PREFERENCES) ?? "[]"
    let delayConditionList: [DelayCondition] = fromJsonArr(json: delayUpdatePreferences).map { obj -> DelayCondition in
      let kind: String = obj.value(forKey: "kind") as! String
      let value: String? = obj.value(forKey: "value") as? String
      return DelayCondition(kind: kind, value: value)
    }
    for condition in delayConditionList {
      let kind: String? = condition.getKind()
      let value: String? = condition.getValue()
      if kind != nil {
        switch kind {
        case "background":
          if !killed {
            self._cancelDelay(source: "background check")
          }
          break
        case "kill":
          if killed {
            self._cancelDelay(source: "kill check")
            // instant install for kill action
            self.installNext()
          }
          break
        case "date":
          if value != nil && value != "" {
            let dateFormatter = ISO8601DateFormatter()
            guard let ExpireDate = dateFormatter.date(from: value!) else {
              self._cancelDelay(source: "date parsing issue")
              return
            }
            if ExpireDate < Date() {
              self._cancelDelay(source: "date expired")
            }
          } else {
            self._cancelDelay(source: "delayVal absent")
          }
          break
        case "nativeVersion":
          if value != nil && value != "" {
            do {
              let versionLimit = try Version(value!)
              if self.currentVersionNative >= versionLimit {
                self._cancelDelay(source: "nativeVersion above limit")
              }
            } catch {
              self._cancelDelay(source: "nativeVersion parsing issue")
            }
          } else {
            self._cancelDelay(source: "delayVal absent")
          }
          break
        case .none:
          print("\(self.implementation.TAG) _checkCancelDelay switch case none error")
        case .some:
          print("\(self.implementation.TAG) _checkCancelDelay switch case some error")
        }
      }
    }
    // self.checkAppReady() why this here?
  }
  
  private func _isAutoUpdateEnabled() -> Bool {
    return self.autoUpdate && self.updateUrl != ""
  }
  
  @objc func isAutoUpdateEnabled(_ call: CAPPluginCall) {
    call.resolve([
      "enabled": self._isAutoUpdateEnabled()
    ])
  }
  
  func checkAppReady() {
    self.appReadyCheck?.cancel()
    self.appReadyCheck = DispatchWorkItem(block: {
      self.DeferredNotifyAppReadyCheck()
    })
    print("\(self.implementation.TAG) Wait for \(self.appReadyTimeout) ms, then check for notifyAppReady")
    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(self.appReadyTimeout), execute: self.appReadyCheck!)
  }
  
  func checkRevert() {
    // Automatically roll back to fallback version if notifyAppReady has not been called yet
    let current: BundleInfo = self.implementation.getCurrentBundle()
    if current.isBuiltin() {
      print("\(self.implementation.TAG) Built-in bundle is active. Nothing to do.")
      return
    }
    
    print("\(self.implementation.TAG) Current bundle is: \(current.toString())")
    
    if BundleStatus.SUCCESS.localizedString != current.getStatus() {
      print("\(self.implementation.TAG) notifyAppReady was not called, roll back current bundle: \(current.toString())")
      print("\(self.implementation.TAG) Did you forget to call 'notifyAppReady()' in your Capacitor App code?")
      self.notifyListeners("updateFailed", data: [
        "bundle": current.toJSON()
      ])
      self.implementation.sendStats(action: "update_fail", versionName: current.getVersionName())
      self.implementation.setError(bundle: current)
      _ = self._reset(toLastSuccessful: true)
      if self.autoDeleteFailed && !current.isBuiltin() {
        print("\(self.implementation.TAG) Deleting failing bundle: \(current.toString())")
        let res = self.implementation.delete(id: current.getId(), removeInfo: false)
        if !res {
          print("\(self.implementation.TAG) Delete version deleted: \(current.toString())")
        } else {
          print("\(self.implementation.TAG) Failed to delete failed bundle: \(current.toString())")
        }
      }
    } else {
      print("\(self.implementation.TAG) notifyAppReady was called. This is fine: \(current.toString())")
    }
  }
  
  func DeferredNotifyAppReadyCheck() {
    self.checkRevert()
    self.appReadyCheck = nil
  }
  
  @objc func appMovedToForeground() {
    if backgroundWork != nil && taskRunning {
      backgroundWork!.cancel()
      print("\(self.implementation.TAG) Background Timer Task canceled, Activity resumed before timer completes")
    }
    if self._isAutoUpdateEnabled() {
      DispatchQueue.global(qos: .background).async {
        print("\(self.implementation.TAG) Check for update via \(self.updateUrl)")
        let url = URL(string: self.updateUrl)!
        let res = self.implementation.getLatest(url: url)
        let current = self.implementation.getCurrentBundle()
        
        if (res.message) != nil {
          print("\(self.implementation.TAG) message \(res.message ?? "")")
          if res.major == true {
            self.notifyListeners("majorAvailable", data: ["version": res.version])
          }
          self.notifyListeners("noNeedUpdate", data: ["bundle": current.toJSON()])
          return
        }
        guard let downloadUrl = URL(string: res.url) else {
          print("\(self.implementation.TAG) Error no url or wrong format")
          self.notifyListeners("noNeedUpdate", data: ["bundle": current.toJSON()])
          return
        }
        let latestVersionName = res.version
        if latestVersionName != "" && current.getVersionName() != latestVersionName {
          let latest = self.implementation.getBundleInfoByVersionName(version: latestVersionName)
          if latest != nil {
            if latest!.isErrorStatus() {
              print("\(self.implementation.TAG) Latest version already exists, and is in error state. Aborting update.")
              self.notifyListeners("noNeedUpdate", data: ["bundle": current.toJSON()])
              return
            }
            if latest!.isDownloaded() {
              print("\(self.implementation.TAG) Latest version already exists and download is NOT required. Update will occur next time app moves to background.")
              self.notifyListeners("updateAvailable", data: ["bundle": current.toJSON()])
              _ = self.implementation.setNextBundle(next: latest!.getId())
              return
            }
            if latest!.isDeleted() {
              print("\(self.implementation.TAG) Latest bundle already exists and will be deleted, download will overwrite it.")
              let res = self.implementation.delete(id: latest!.getId(), removeInfo: true)
              if !res {
                print("\(self.implementation.TAG) Delete version deleted: \(latest!.toString())")
              } else {
                print("\(self.implementation.TAG) Failed to delete failed bundle: \(latest!.toString())")
              }
            }
          }
          
          print("\(self.implementation.TAG) New bundle: \(latestVersionName) found. Current is: \(current.getVersionName()). Update will occur next time app moves to background.")
          let promise = self.implementation.download(url: downloadUrl, version: latestVersionName)
          promise.observe { result in
            switch result {
            case .success(let next):
              if res.checksum != "" && next.getChecksum() != res.checksum {
                print("\(self.implementation.TAG) Error checksum", next.getChecksum(), res.checksum)
                let resDel = self.implementation.delete(id: next.getId())
                if !resDel {
                  print("\(self.implementation.TAG) Delete failed, id \(next.getId()) doesn't exist")
                }
                return
              }
              self.notifyListeners("updateAvailable", data: ["bundle": next.toJSON()])
              _ = self.implementation.setNextBundle(next: next.getId())
            case .failure(let error):
              print("\(self.implementation.TAG) Error downloading file", error.localizedDescription)
              let current: BundleInfo = self.implementation.getCurrentBundle()
              self.implementation.sendStats(action: "download_fail", versionName: current.getVersionName())
              self.notifyListeners("downloadFailed", data: ["version": latestVersionName])
              self.notifyListeners("noNeedUpdate", data: ["bundle": current.toJSON()])
            }
          }
          
        } else {
          print("\(self.implementation.TAG) No need to update, \(current.getId()) is the latest bundle.")
          self.notifyListeners("noNeedUpdate", data: ["bundle": current.toJSON()])
        }
      }
    }
    
    self.checkAppReady()
  }
  
  @objc func appMovedToBackground() {
    print("\(self.implementation.TAG) Check for pending update")
    let delayUpdatePreferences = UserDefaults.standard.string(forKey: DELAY_CONDITION_PREFERENCES) ?? "[]"
    
    let delayConditionList: [DelayCondition] = fromJsonArr(json: delayUpdatePreferences).map { obj -> DelayCondition in
      let kind: String = obj.value(forKey: "kind") as! String
      let value: String? = obj.value(forKey: "value") as? String
      return DelayCondition(kind: kind, value: value)
    }
    var backgroundValue: String?
    for delayCondition in delayConditionList {
      if delayCondition.getKind() == "background" {
        let value: String? = delayCondition.getValue()
        backgroundValue = (value != nil && value != "") ? value! : "0"
      }
    }
    if backgroundValue != nil {
      self.taskRunning = true
      let interval: Double = (Double(backgroundValue!) ?? 0.0) / 1000
      self.backgroundWork?.cancel()
      self.backgroundWork = DispatchWorkItem(block: {
        // IOS never executes this task in background
        self.taskRunning = false
        self._checkCancelDelay(killed: false)
        self.installNext()
      })
      DispatchQueue.global(qos: .background).asyncAfter(deadline: .now() + interval, execute: self.backgroundWork!)
    } else {
      self._checkCancelDelay(killed: false)
      self.installNext()
    }
    
  }
  
  @objc func appKilled() {
    self._checkCancelDelay(killed: true)
  }
  
  private func installNext() {
    let delayUpdatePreferences = UserDefaults.standard.string(forKey: DELAY_CONDITION_PREFERENCES) ?? "[]"
    let delayConditionList: [DelayCondition]? = fromJsonArr(json: delayUpdatePreferences).map { obj -> DelayCondition in
      let kind: String = obj.value(forKey: "kind") as! String
      let value: String? = obj.value(forKey: "value") as? String
      return DelayCondition(kind: kind, value: value)
    }
    if delayConditionList != nil && delayConditionList?.capacity != 0 {
      print("\(self.implementation.TAG) Update delayed to next backgrounding")
      return
    }
    let current: BundleInfo = self.implementation.getCurrentBundle()
    let next: BundleInfo? = self.implementation.getNextBundle()
    
    if next != nil && !next!.isErrorStatus() && next!.getVersionName() != current.getVersionName() {
      print("\(self.implementation.TAG) Next bundle is: \(next!.toString())")
      if self.implementation.set(bundle: next!) && self._reload() {
        print("\(self.implementation.TAG) Updated to bundle: \(next!.toString())")
        _ = self.implementation.setNextBundle(next: Optional<String>.none)
      } else {
        print("\(self.implementation.TAG) Update to bundle: \(next!.toString()) Failed!")
      }
    }
  }
  
  @objc private func toJson(object: Any) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: object, options: []) else {
      return ""
    }
    return String(data: data, encoding: String.Encoding.utf8) ?? ""
  }
  
  @objc private func fromJsonArr(json: String) -> [NSObject] {
    let jsonData = json.data(using: .utf8)!
    let object = try? JSONSerialization.jsonObject(
      with: jsonData,
      options: .mutableContainers
    ) as? [NSObject]
    return object ?? []
  }
}
