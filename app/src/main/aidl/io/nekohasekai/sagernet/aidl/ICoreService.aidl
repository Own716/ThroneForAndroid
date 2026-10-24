package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISubscriptionCallback;
import io.nekohasekai.sagernet.aidl.ITestSessionCallback;
import io.nekohasekai.sagernet.bg.test.TestSpec;

interface ICoreService {
  // Test sessions: returns the session id, -1 while another session runs; stopTests stops the running one.
  int startTest(in TestSpec spec, ITestSessionCallback cb);
  void stopTests();
  int runningTestSession();

  // Subscriptions (desktop GroupUpdater queue).
  void refreshGroup(long gid, boolean showDiff);
  void refreshAll(boolean onlyAllowed);
  long subscribeUrl(String url, String name, boolean autoUpdate);
  void importUrl(String url, long gid);
  void importFile(String path, long gid);
  String groupAction(long gid, String action);
  String resolveProfiles(in long[] ids);
  void registerSubscriptionCallback(ISubscriptionCallback cb);
  void unregisterSubscriptionCallback(ISubscriptionCallback cb);

  // WARP: blocks up to 10 s + 10 s per API host; returns JSON (see WarpRegistration).
  String warpRegister(String tunnelType, String proxy, in String[] apiHosts);

  // AmneziaWG I1..I5 from a QUIC handshake to domain, built offline; returns JSON (see QuicSignature).
  String genQuicSignature(String domain);

  // Performance profile of :bg: blocks for the window (at most 60 s) or until stopDiagnostics, attaches each file of
  // attachmentPaths by its name and writes the zip to outputPath; returns "" or the error (see CoreDiagnostics).
  String captureDiagnostics(int durationMs, boolean contention, boolean trace, in String[] attachmentPaths, String outputPath);
  void stopDiagnostics();
}
