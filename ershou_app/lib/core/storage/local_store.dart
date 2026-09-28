import 'package:shared_preferences/shared_preferences.dart';

/// 键值存储（服务器地址、token、当前用户快照）。
class LocalStore {
  LocalStore(this._prefs);

  static const _tokenKey = 'ershou.token';
  static const _userKey = 'ershou.user';

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

  Future<void> clearSession() async {
    await _prefs.remove(_tokenKey);
    await _prefs.remove(_userKey);
  }
}
