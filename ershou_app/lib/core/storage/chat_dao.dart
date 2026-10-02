import 'package:sqflite/sqflite.dart';

import '../../data/models2.dart';

/// 消息行主键：服务端消息用 `s:<id>`，本地乐观消息用 `c:<clientMsgId>`。
/// 这样"发送中 → 收到 ack"的替换就是一次主键覆盖，不依赖顺序或时间去猜。
String messageLocalKey(ChatMessage m) =>
    m.fromServer ? 's:' + m.id.toString() : 'c:' + m.clientMsgId;

int createdAtMs(String raw, {required int fallback}) =>
    DateTime.tryParse(raw)?.millisecondsSinceEpoch ?? fallback;

Map<String, Object?> messageToRow(String scope, ChatMessage m, {required int nowMs}) {
  final ms = m.fromServer ? createdAtMs(m.createdAt, fallback: nowMs) : createdAtMs(m.createdAt, fallback: nowMs);
  return {
    'scope': scope,
    'local_key': messageLocalKey(m),
    'id': m.fromServer ? m.id : null,
    'session_id': m.sessionId,
    'sender_id': m.senderId,
    'mine': m.mine ? 1 : 0,
    'msg_type': m.msgType,
    'content': m.recalled ? '' : m.content,
    'recalled': m.recalled ? 1 : 0,
    'risk_warning': m.riskWarning,
    'client_msg_id': m.clientMsgId,
    'created_at': m.createdAt,
    'created_at_ms': ms,
    'local_status': m.localStatus,
  };
}

ChatMessage messageFromRow(Map<String, Object?> row) => ChatMessage(
      id: (row['id'] as num?)?.toInt() ?? 0,
      sessionId: (row['session_id'] as num?)?.toInt() ?? 0,
      senderId: (row['sender_id'] as num?)?.toInt() ?? 0,
      msgType: (row['msg_type'] as num?)?.toInt() ?? 1,
      content: (row['content'] as String?) ?? '',
      mine: ((row['mine'] as num?)?.toInt() ?? 0) == 1,
      recalled: ((row['recalled'] as num?)?.toInt() ?? 0) == 1,
      riskWarning: (row['risk_warning'] as String?) ?? '',
      clientMsgId: (row['client_msg_id'] as String?) ?? '',
      createdAt: (row['created_at'] as String?) ?? '',
      localStatus: (row['local_status'] as String?) ?? 'sent',
    );

Map<String, Object?> sessionToRow(String scope, ChatSession s, {required int nowMs}) => {
      'scope': scope,
      'session_id': s.id,
      'peer_id': s.peerId,
      'peer_name': s.peerName,
      'peer_avatar': s.peerAvatar,
      'peer_credit': s.peerCreditScore,
      'peer_online': s.peerOnline ? 1 : 0,
      'item_id': s.itemId,
      'item_title': s.itemTitle,
      'item_cover': s.itemCover,
      'item_price': s.itemPrice,
      'item_status_text': s.itemStatusText,
      'my_role': s.myRole,
      'blocked_by_me': s.blockedByMe ? 1 : 0,
      'blocked_by_peer': s.blockedByPeer ? 1 : 0,
      'can_send': s.canSend ? 1 : 0,
      'last_msg_text': s.lastMsgText,
      'last_msg_at': s.lastMsgAt,
      'last_msg_at_ms': createdAtMs(s.lastMsgAt, fallback: 0),
      'unread': s.unread,
      'updated_at_ms': nowMs,
    };

ChatSession sessionFromRow(Map<String, Object?> row) => ChatSession(
      id: (row['session_id'] as num?)?.toInt() ?? 0,
      peerId: (row['peer_id'] as num?)?.toInt() ?? 0,
      peerName: (row['peer_name'] as String?) ?? '',
      peerAvatar: (row['peer_avatar'] as String?) ?? '',
      peerCreditScore: (row['peer_credit'] as num?)?.toInt() ?? 0,
      peerOnline: ((row['peer_online'] as num?)?.toInt() ?? 0) == 1,
      itemId: (row['item_id'] as num?)?.toInt(),
      itemTitle: (row['item_title'] as String?) ?? '',
      itemCover: (row['item_cover'] as String?) ?? '',
      itemPrice: ((row['item_price'] as num?) ?? 0).toDouble(),
      itemStatusText: (row['item_status_text'] as String?) ?? '',
      myRole: (row['my_role'] as String?) ?? '',
      blockedByMe: ((row['blocked_by_me'] as num?)?.toInt() ?? 0) == 1,
      blockedByPeer: ((row['blocked_by_peer'] as num?)?.toInt() ?? 0) == 1,
      canSend: ((row['can_send'] as num?)?.toInt() ?? 1) == 1,
      lastMsgText: (row['last_msg_text'] as String?) ?? '',
      lastMsgAt: (row['last_msg_at'] as String?) ?? '',
      unread: (row['unread'] as num?)?.toInt() ?? 0,
    );

/// 聊天镜像表的读写。SQL 保持"一表一主键 + 一次 upsert"，合并规则在领域层。
class ChatDao {
  ChatDao(this._db);

  final Database _db;

  static const tableSession = 'chat_session';
  static const tableMessage = 'chat_message';
  static const tableSync = 'chat_sync';

  Future<void> saveSessions(String scope, List<ChatSession> list) async {
    if (list.isEmpty) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    final batch = _db.batch();
    for (final s in list) {
      batch.insert(tableSession, sessionToRow(scope, s, nowMs: now),
          conflictAlgorithm: ConflictAlgorithm.replace);
    }
    await batch.commit(noResult: true);
  }

  Future<List<ChatSession>> loadSessions(String scope, {int limit = 100}) async {
    final rows = await _db.query(
      tableSession,
      where: 'scope = ?',
      whereArgs: [scope],
      orderBy: 'last_msg_at_ms DESC, session_id DESC',
      limit: limit,
    );
    return rows.map(sessionFromRow).toList();
  }

  /// 写入消息。带 clientMsgId 的服务端消息会顺手删掉本地那条乐观行。
  Future<void> saveMessages(String scope, int sessionId, List<ChatMessage> list) async {
    if (list.isEmpty) return;
    final now = DateTime.now().millisecondsSinceEpoch;
    var maxId = 0;
    final batch = _db.batch();
    for (final m in list) {
      if (m.fromServer) {
        if (m.clientMsgId.isNotEmpty) {
          batch.delete(tableMessage,
              where: 'scope = ? AND local_key = ?',
              whereArgs: [scope, 'c:' + m.clientMsgId]);
        }
        if (m.id > maxId) maxId = m.id;
      }
      batch.insert(tableMessage, messageToRow(scope, m, nowMs: now),
          conflictAlgorithm: ConflictAlgorithm.replace);
    }
    if (maxId > 0) {
      final current = await maxServerId(scope, sessionId);
      batch.insert(
        tableSync,
        {
          'scope': scope,
          'session_id': sessionId,
          'last_msg_id': maxId > current ? maxId : current,
          'last_sync_ms': now,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    }
    await batch.commit(noResult: true);
  }

  /// 最近 [limit] 条，按时间正序返回（界面直接用）。
  Future<List<ChatMessage>> loadMessages(String scope, int sessionId, {int limit = 50}) async {
    final rows = await _db.query(
      tableMessage,
      where: 'scope = ? AND session_id = ?',
      whereArgs: [scope, sessionId],
      orderBy: 'created_at_ms DESC, id DESC',
      limit: limit,
    );
    return rows.reversed.map(messageFromRow).toList();
  }

  Future<void> setLocalStatus(String scope, String localKey, String status) async {
    await _db.update(
      tableMessage,
      {'local_status': status},
      where: 'scope = ? AND local_key = ?',
      whereArgs: [scope, localKey],
    );
  }

  /// 撤回：原文不再留在设备上（服务端也是把 content 置空）。
  Future<void> markRecalled(String scope, int messageId) async {
    await _db.update(
      tableMessage,
      {'recalled': 1, 'content': ''},
      where: 'scope = ? AND id = ?',
      whereArgs: [scope, messageId],
    );
  }

  Future<void> markSessionRead(String scope, int sessionId) async {
    await _db.update(
      tableSession,
      {'unread': 0},
      where: 'scope = ? AND session_id = ?',
      whereArgs: [scope, sessionId],
    );
  }

  /// 超过 [after] 还没拿到结果的"发送中"行标记为失败（避免永远转圈）。
  Future<int> failStaleSending(String scope, {Duration after = const Duration(seconds: 30)}) async {
    final deadline = DateTime.now().subtract(after).millisecondsSinceEpoch;
    return _db.update(
      tableMessage,
      {'local_status': 'failed'},
      where: 'scope = ? AND local_status = ? AND created_at_ms < ?',
      whereArgs: [scope, 'sending', deadline],
    );
  }

  Future<int> maxServerId(String scope, int sessionId) async {
    final rows = await _db.rawQuery(
      'SELECT MAX(id) AS max_id FROM ' + tableMessage + ' WHERE scope = ? AND session_id = ?',
      [scope, sessionId],
    );
    return (rows.first['max_id'] as num?)?.toInt() ?? 0;
  }

  /// 每会话只留最近 [keep] 条已落库消息；发送中/失败的本地行永不裁剪。
  Future<void> trimSession(String scope, int sessionId, {int keep = 300}) async {
    await _db.rawDelete(
      'DELETE FROM ' + tableMessage + ' WHERE scope = ? AND session_id = ? '
      "AND local_status = 'sent' AND id IS NOT NULL AND id NOT IN ("
      'SELECT id FROM ' + tableMessage + ' WHERE scope = ? AND session_id = ? AND id IS NOT NULL '
      'ORDER BY id DESC LIMIT ?)',
      [scope, sessionId, scope, sessionId, keep],
    );
  }

  /// 给一个 scope 下的每个会话各裁剪一次（App 启动空闲时调用）。
  Future<void> trimScope(String scope, {int keep = 300}) async {
    final rows = await _db.rawQuery(
      'SELECT DISTINCT session_id FROM ' + tableMessage + ' WHERE scope = ?',
      [scope],
    );
    for (final row in rows) {
      final sessionId = (row['session_id'] as num?)?.toInt() ?? 0;
      if (sessionId > 0) await trimSession(scope, sessionId, keep: keep);
    }
  }

  /// 删除**单个会话**的本地镜像（会话行 + 该会话的全部消息 + 同步水位）。
  ///
  /// <p>为什么必须一起删：服务端删除只是把会话从"我的列表"里摘掉，本地库如果还留着
  /// 会话行与消息，下次冷启动离线打开时会**先渲染本地镜像**，用户就会看到
  /// "明明删了却又回来了" —— 这是本地镜像与远端不一致导致的假 bug。
  /// 三个表都要清：会话行消失但消息还在，会留下永远读不到的孤儿消息占空间。
  Future<void> deleteSession(String scope, int sessionId) async {
    final batch = _db.batch();
    batch.delete(tableSession, where: 'scope = ? AND session_id = ?', whereArgs: [scope, sessionId]);
    batch.delete(tableMessage, where: 'scope = ? AND session_id = ?', whereArgs: [scope, sessionId]);
    batch.delete(tableSync, where: 'scope = ? AND session_id = ?', whereArgs: [scope, sessionId]);
    await batch.commit(noResult: true);
  }

  Future<void> clearScope(String scope) async {
    final batch = _db.batch();
    for (final table in [tableMessage, tableSession, tableSync]) {
      batch.delete(table, where: 'scope = ?', whereArgs: [scope]);
    }
    await batch.commit(noResult: true);
  }
}
