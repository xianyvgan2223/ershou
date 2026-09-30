import 'dart:io';

import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart';

/// 聊天本地镜像库（纯缓存，数据源永远在服务端）：
/// `<App 私有目录>/ershou_chat.db`，放应用私有目录，不进外部存储。
///
/// 迁移策略：缓存可丢 —— 升级/降级时先按增量迁移，任何异常直接重建空库，
/// 不做"为缓存数据写复杂迁移"这种事。
class ChatDatabase {
  ChatDatabase._();

  static const fileName = 'ershou_chat.db';
  static const version = 1;

  static Future<Database> open() async {
    final dir = await getApplicationSupportDirectory();
    final path = dir.path + Platform.pathSeparator + fileName;
    return openDatabase(
      path,
      version: version,
      onCreate: (db, _) => _create(db),
      onUpgrade: _upgrade,
      onDowngrade: (db, _, __) => _recreate(db),
    );
  }

  static Future<void> _create(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS chat_session (
        scope            TEXT    NOT NULL,
        session_id       INTEGER NOT NULL,
        peer_id          INTEGER NOT NULL DEFAULT 0,
        peer_name        TEXT    NOT NULL DEFAULT '',
        peer_avatar      TEXT    NOT NULL DEFAULT '',
        peer_credit      INTEGER NOT NULL DEFAULT 0,
        peer_online      INTEGER NOT NULL DEFAULT 0,
        item_id          INTEGER,
        item_title       TEXT    NOT NULL DEFAULT '',
        item_cover       TEXT    NOT NULL DEFAULT '',
        item_price       REAL    NOT NULL DEFAULT 0,
        item_status_text TEXT    NOT NULL DEFAULT '',
        my_role          TEXT    NOT NULL DEFAULT '',
        blocked_by_me    INTEGER NOT NULL DEFAULT 0,
        blocked_by_peer  INTEGER NOT NULL DEFAULT 0,
        can_send         INTEGER NOT NULL DEFAULT 1,
        last_msg_text    TEXT    NOT NULL DEFAULT '',
        last_msg_at      TEXT    NOT NULL DEFAULT '',
        last_msg_at_ms   INTEGER NOT NULL DEFAULT 0,
        unread           INTEGER NOT NULL DEFAULT 0,
        updated_at_ms    INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (scope, session_id)
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS chat_message (
        scope          TEXT    NOT NULL,
        local_key      TEXT    NOT NULL,
        id             INTEGER,
        session_id     INTEGER NOT NULL,
        sender_id      INTEGER NOT NULL DEFAULT 0,
        mine           INTEGER NOT NULL DEFAULT 0,
        msg_type       INTEGER NOT NULL DEFAULT 1,
        content        TEXT    NOT NULL DEFAULT '',
        recalled       INTEGER NOT NULL DEFAULT 0,
        risk_warning   TEXT    NOT NULL DEFAULT '',
        client_msg_id  TEXT    NOT NULL DEFAULT '',
        created_at     TEXT    NOT NULL DEFAULT '',
        created_at_ms  INTEGER NOT NULL DEFAULT 0,
        local_status   TEXT    NOT NULL DEFAULT 'sent',
        PRIMARY KEY (scope, local_key)
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_msg_page ON chat_message(scope, session_id, created_at_ms, id)',
    );
    await db.execute(
      'CREATE UNIQUE INDEX IF NOT EXISTS uk_msg_server ON chat_message(scope, id) WHERE id IS NOT NULL',
    );
    await db.execute('''
      CREATE TABLE IF NOT EXISTS chat_sync (
        scope        TEXT    NOT NULL,
        session_id   INTEGER NOT NULL,
        last_msg_id  INTEGER NOT NULL DEFAULT 0,
        last_sync_ms INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (scope, session_id)
      )
    ''');
  }

  /// 版本升级：目前只有 v1；后续新增列/表写在这里，失败一律重建。
  static Future<void> _upgrade(Database db, int oldVersion, int newVersion) async {
    try {
      // 预留：if (oldVersion < 2) { await db.execute('ALTER TABLE ...'); }
      if (oldVersion < version) {
        await _recreate(db);
      }
    } catch (_) {
      await _recreate(db);
    }
  }

  static Future<void> _recreate(Database db) async {
    await db.execute('DROP TABLE IF EXISTS chat_message');
    await db.execute('DROP TABLE IF EXISTS chat_session');
    await db.execute('DROP TABLE IF EXISTS chat_sync');
    await _create(db);
  }
}
