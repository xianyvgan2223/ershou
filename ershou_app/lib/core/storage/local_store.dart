import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

/// 键值存储：登录态、用户快照、以及几处「小对象」读缓存。
///
/// 聊天消息/会话的镜像不放这里（那是 sqflite 的事，见 core/storage/chat_cache.dart），
/// 这里只放几十字节到几 KB 的配置与列表缓存。
class LocalStore {
  LocalStore(this._prefs);

  static const _tokenKey = 'ershou.token';
  static const _userKey = 'ershou.user';
  static const _userIdKey = 'ershou.userId';
  static const _cachePrefix = 'ershou.cache.';

  final SharedPreferences _prefs;

  String? get token => _prefs.getString(_tokenKey);

  Future<void> setToken(String? token) async {
    if (token == null || token.isEmpty) {
      await _prefs.remove(_tokenKey);
    } else {
      await _prefs.setString(_tokenKey, token);
    }
  }

  String? get userJson => _prefs.getString(_userKey);

  Future<void> setUserJson(String? json) async {
    if (json == null || json.isEmpty) {
      await _prefs.remove(_userKey);
    } else {
      await _prefs.setString(_userKey, json);
    }
  }

  /// 当前登录用户 id：本地缓存按「服务器 + 用户」分区，冷启动就要能算出来。
  int get userId => _prefs.getInt(_userIdKey) ?? 0;

  Future<void> setUserId(int? id) async {
    if (id == null || id <= 0) {
      await _prefs.remove(_userIdKey);
    } else {
      await _prefs.setInt(_userIdKey, id);
    }
  }

  /// 带时间戳的小对象缓存（分类、商品详情首屏）。
  Future<void> setJsonCache(String key, Object? value) async {
    if (value == null) {
      await _prefs.remove(_cachePrefix + key);
      return;
    }
    await _prefs.setString(
      _cachePrefix + key,
      jsonEncode({'at': DateTime.now().millisecondsSinceEpoch, 'v': value}),
    );
  }

  /// 读取缓存；[maxAge] 超时返回 null（不删除，下次仍可作兜底，按需自行决定）。
  Object? jsonCache(String key, {Duration? maxAge}) {
    final raw = _prefs.getString(_cachePrefix + key);
    if (raw == null || raw.isEmpty) return null;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return null;
      if (maxAge != null) {
        final at = (decoded['at'] as num?)?.toInt() ?? 0;
        final age = DateTime.now().millisecondsSinceEpoch - at;
        if (age > maxAge.inMilliseconds) return null;
      }
      return decoded['v'];
    } catch (_) {
      return null;
    }
  }

  Future<void> removeJsonCache(String key) => _prefs.remove(_cachePrefix + key);

  Future<void> clearSession() async {
    await _prefs.remove(_tokenKey);
    await _prefs.remove(_userKey);
    await _prefs.remove(_userIdKey);
  }
}
