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
  ///
  /// ⚠️ 必须容错：这个 getter 在 `main()` 里就被调用，一旦抛异常**整个 App 白屏**
  /// （2026-10-02 实测踩过：外部注入的 prefs 把 userId 写成了字符串，`getInt` 直接
  /// 抛 `type 'String' is not a subtype of int`，`main()` 挂掉、界面一片白）。
  /// 存储里的脏数据不该让 App 起不来 —— 读不出来就当没登录，用户重新登录即可。
  int get userId {
    try {
      return _prefs.getInt(_userIdKey) ?? 0;
    } catch (_) {
      // 类型不对（脏数据 / 旧版本写入格式不同）：清掉，别让它每次都炸
      _prefs.remove(_userIdKey);
      return 0;
    }
  }

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

  // ---------------------------------------------------------------- 界面偏好

  /// 界面偏好（"我附近"是否折叠这类用户自己选的小开关）。
  ///
  /// 单独用 `ershou.ui.` 前缀，便于一眼看出哪些是纯展示层的东西。
  /// 读的时候**带类型兜底**：存储里是脏数据就返回默认值，而不是抛异常 ——
  /// 这类 getter 可能在 build 里被调用，抛出去就是整页白屏（这个坑踩过一次）。
  static const _uiPrefix = 'ershou.ui.';

  bool uiFlag(String key, {bool fallback = false}) {
    try {
      return _prefs.getBool(_uiPrefix + key) ?? fallback;
    } catch (_) {
      _prefs.remove(_uiPrefix + key);
      return fallback;
    }
  }

  Future<void> setUiFlag(String key, bool value) async {
    try {
      await _prefs.setBool(_uiPrefix + key, value);
    } catch (_) {
      // 偏好写不进去不值得影响主流程
    }
  }

  Future<void> clearSession() async {
    await _prefs.remove(_tokenKey);
    await _prefs.remove(_userKey);
    await _prefs.remove(_userIdKey);
  }
}
