import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../config/server_config.dart';
import 'chat_events.dart';

/// 私聊 WebSocket：ws://host:8080/ws/chat?token=xxx（Sa-Token 握手鉴权）。
///
/// 职责边界（对齐 Web 端 ershou-web/src/stores/chat.ts）：
///  - 只负责「连接保活 + 收发帧」，业务分发交给 ChatEventRouter 与聊天页；
///  - 发包仍走 REST（后端 REST 与 WS 是同一条业务链路，REST 有明确返回值与错误码），
///    所以这里只发 ping；
///  - 30 秒心跳：服务端在线状态是 90 秒 TTL，不 ping 的话对方列表里你一直显示离线；
///  - 75 秒收不到任何帧视为半死连接，主动重连。
class ChatSocket {
  ChatSocket(this.config, this.token);

  static const heartbeatInterval = Duration(seconds: 30);
  static const idleTimeout = Duration(seconds: 75);
  static const _connectTimeout = Duration(seconds: 6);

  final ServerConfig config;
  final String token;

  WebSocket? _socket;
  StreamSubscription<dynamic>? _sub;
  Timer? _retry;
  Timer? _heartbeat;
  DateTime _lastFrameAt = DateTime.now();
  int _attempt = 0;
  bool _closed = false;
  bool _online = false;

  final _frames = StreamController<ChatFrame>.broadcast();
  final _status = StreamController<bool>.broadcast();

  /// 已解析的帧流（ready/pong/message/ack/recall/read/read-ack/error）。
  Stream<ChatFrame> get frames => _frames.stream;

  /// 在线状态变化（连上 / 断开）。
  Stream<bool> get connected => _status.stream;

  bool get isOnline => _online;

  /// 距最后一次收到帧的时长：聊天页据此决定是否要开轮询兜底。
  Duration get silence => DateTime.now().difference(_lastFrameAt);

  Future<void> connect() async {
    if (_closed || token.isEmpty || _socket != null) return;
    try {
      final socket = await WebSocket.connect(
        config.chatWs(token).toString(),
      ).timeout(_connectTimeout);
      if (_closed) {
        await socket.close();
        return;
      }
      _socket = socket;
      _attempt = 0;
      _lastFrameAt = DateTime.now();
      _setOnline(true);
      _startHeartbeat();
      _sub = socket.listen(
        (dynamic raw) {
          final frame = ChatFrame.decode(raw);
          if (frame == null) return;
          _lastFrameAt = DateTime.now();
          if (!_frames.isClosed) _frames.add(frame);
        },
        onDone: _scheduleReconnect,
        onError: (Object _) => _scheduleReconnect(),
        cancelOnError: true,
      );
    } catch (_) {
      _setOnline(false);
      _scheduleReconnect();
    }
  }

  /// 页面回到前台 / 图标重进时调用：立刻重连并重置退避。
  void ensureConnected() {
    if (_closed || token.isEmpty || _socket != null) return;
    _attempt = 0;
    _retry?.cancel();
    _retry = null;
    unawaited(connect());
  }

  bool send(Map<String, dynamic> payload) {
    final socket = _socket;
    if (socket == null) return false;
    try {
      socket.add(jsonEncode(payload));
      return true;
    } catch (_) {
      return false;
    }
  }

  void _startHeartbeat() {
    _heartbeat?.cancel();
    _heartbeat = Timer.periodic(heartbeatInterval, (_) {
      if (_socket == null) return;
      if (silence > idleTimeout) {
        // 连接半死（TCP 还在但对端不可达）：靠 ping 发现不了，直接重连
        _scheduleReconnect();
        return;
      }
      send({'type': ChatFrameType.ping});
    });
  }

  /// 退避重连：1s, 2s, 4s, 8s（和 Web 端、旧实现保持一致）。
  void _scheduleReconnect() {
    if (_closed) return;
    _setOnline(false);
    _heartbeat?.cancel();
    _heartbeat = null;
    _sub?.cancel();
    _sub = null;
    final socket = _socket;
    _socket = null;
    socket?.close().ignore();
    _attempt = (_attempt + 1).clamp(1, 4);
    final delay = Duration(seconds: 1 << (_attempt - 1));
    _retry?.cancel();
    _retry = Timer(delay, connect);
  }

  void _setOnline(bool value) {
    if (_online == value) return;
    _online = value;
    if (!_status.isClosed) _status.add(value);
  }

  Future<void> dispose() async {
    _closed = true;
    _retry?.cancel();
    _heartbeat?.cancel();
    await _sub?.cancel();
    await _socket?.close();
    await _frames.close();
    await _status.close();
  }
}
