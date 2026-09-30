import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'core/config/server_config_service.dart';
import 'core/providers.dart';
import 'core/storage/chat_cache.dart';
import 'core/storage/local_store.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  // 聊天本地镜像：打不开（平台不支持/磁盘异常）就为 null，App 退化成无缓存但功能正常
  final chatCache = await ChatCache.open();
  if (chatCache != null) {
    final scope = chatScopeOf(
      origin: ServerConfigService(prefs).load()?.origin ?? '',
      userId: LocalStore(prefs).userId,
    );
    // 上次运行的镜像顺手裁剪一次（每会话留最近 300 条）
    if (scope.isNotEmpty) unawaited(chatCache.trim(scope));
  }
  runApp(
    ProviderScope(
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        chatCacheProvider.overrideWithValue(chatCache),
      ],
      child: const ErshouApp(),
    ),
  );
}
