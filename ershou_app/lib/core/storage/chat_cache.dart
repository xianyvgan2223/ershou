import 'package:sqflite/sqflite.dart';

import '../../data/models2.dart';
import 'chat_dao.dart';
import 'chat_database.dart';

/// 本地缓存的命名空间：`<服务器 origin>|<用户 id>`。
/// 换服务器、切账号天然隔离；任一为空时返回 '' = 不缓存（比如冷启动还没拿到用户 id）。
String chatScopeOf({required String origin, required int userId}) {
  if (origin.isEmpty || userId <= 0) return '';
  return origin + '|' + userId.toString();
}

/// 把服务端返回的窗口合并进本地列表（纯函数，单测覆盖）。
///
/// 规则（对应方案 `3.5）：
///  1. 服务端消息按 id 覆盖本地同 id 行（撤回态、风控文案以服务端为准）；
///  2. 服务端消息带 clientMsgId 时，替换本地那条"发送中"的乐观消息；
///  3. 本地多出来的行（没回包的乐观消息、发送失败的）**保留** —— 服务端窗口是
///     "最近 N 条"，也可能因为风控把某些消息过滤掉，用它反推删除会丢消息；
///  4. 最终按时间正序（同毫秒用服务端 id 兜底）。
List<ChatMessage> mergeMessages({
  required List<ChatMessage> local,
  required List<ChatMessage> incoming,
}) {
  final merged = <ChatMessage>[...local];
  for (final msg in incoming) {
    var index = -1;
    if (msg.fromServer) {
      index = merged.indexWhere((m) => m.id == msg.id);
    }
    if (index < 0 && msg.clientMsgId.isNotEmpty) {
      index = merged.indexWhere((m) => !m.fromServer && m.clientMsgId == msg.clientMsgId);
    }
    if (index >= 0) {
      merged[index] = msg;
    } else {
      merged.add(msg);
    }
  }
  merged.sort((a, b) {
    final diff = _createdAtMs(a).compareTo(_createdAtMs(b));
    if (diff != 0) return diff;
    return a.id.compareTo(b.id);
  });
  return merged;
}

/// 把服务端会话列表合并进本地列表：以服务端为准覆盖，本地多出来的（新会话）保留。
List<ChatSession> mergeSessions({
  required List<ChatSession> local,
  required List<ChatSession> incoming,
}) {
  final merged = <ChatSession>[...local];
  for (final session in incoming) {
    final index = merged.indexWhere((s) => s.id == session.id);
    if (index >= 0) {
      merged[index] = session;
    } else {
      merged.add(session);
    }
  }
  merged.sort((a, b) {
    final diff = _parseMs(b.lastMsgAt).compareTo(_parseMs(a.lastMsgAt));
    if (diff != 0) return diff;
    return b.id.compareTo(a.id);
  });
  return merged;
}

int _createdAtMs(ChatMessage m) => _parseMs(m.createdAt);

int _parseMs(String raw) => DateTime.tryParse(raw)?.millisecondsSinceEpoch ?? 0;

/// 聊天本地镜像的门面：UI 与事件路由只跟它打交道。
class ChatCache {
  ChatCache._(this._db) : _dao = ChatDao(_db);

  final Database _db;
  final ChatDao _dao;

  /// 打不开（平台不支持/磁盘异常）就返回 null，App 退化成"没有本地缓存"，不影响功能。
  static Future<ChatCache?> open() async {
    try {
      return ChatCache._(await ChatDatabase.open());
    } catch (_) {
      return null;
    }
  }

  Future<void> close() => _db.close();

  Future<List<ChatSession>> sessions(String scope) async {
    if (scope.isEmpty) return const [];
    return _dao.loadSessions(scope);
  }

  Future<void> saveSessions(String scope, List<ChatSession> list) async {
    if (scope.isEmpty) return;
    await _dao.saveSessions(scope, list);
  }

  Future<List<ChatMessage>> messages(String scope, int sessionId, {int limit = 50}) async {
    if (scope.isEmpty) return const [];
    return _dao.loadMessages(scope, sessionId, limit: limit);
  }

  Future<void> saveMessages(String scope, int sessionId, List<ChatMessage> list) async {
    if (scope.isEmpty) return;
    await _dao.saveMessages(scope, sessionId, list);
  }

  Future<void> setLocalStatus(String scope, String localKey, String status) async {
    if (scope.isEmpty) return;
    await _dao.setLocalStatus(scope, localKey, status);
  }

  Future<void> markRecalled(String scope, int messageId) async {
    if (scope.isEmpty) return;
    await _dao.markRecalled(scope, messageId);
  }

  Future<void> markRead(String scope, int sessionId) async {
    if (scope.isEmpty) return;
    await _dao.markSessionRead(scope, sessionId);
  }

  /// 发送中超过 30 秒仍未回包 → 失败（返回被改的行数，UI 据此刷新）。
  Future<int> failStaleSending(String scope) async {
    if (scope.isEmpty) return 0;
    return _dao.failStaleSending(scope);
  }

  Future<int> maxServerId(String scope, int sessionId) async {
    if (scope.isEmpty) return 0;
    return _dao.maxServerId(scope, sessionId);
  }

  /// 每会话裁剪到最近 [keep] 条。
  Future<void> trim(String scope, {int keep = 300}) async {
    if (scope.isEmpty) return;
    await _dao.trimScope(scope, keep: keep);
  }

  Future<void> clearScope(String scope) async {
    if (scope.isEmpty) return;
    await _dao.clearScope(scope);
  }
}
