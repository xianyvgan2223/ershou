import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/providers.dart';
import 'ui/pages/connect_page.dart';
import 'ui/pages/home_page.dart';
import 'ui/pages/login_page.dart';

/// 全局导航 key：退出登录 / 连接失效时可以从任意位置回到连接页。
final GlobalKey<NavigatorState> appNavigatorKey = GlobalKey<NavigatorState>();

class ErshouApp extends ConsumerWidget {
  const ErshouApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final server = ref.watch(activeServerProvider);
    final auth = ref.watch(authProvider);
    return MaterialApp(
      title: '校园二手',
      debugShowCheckedModeBanner: false,
      navigatorKey: appNavigatorKey,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2E7D32)),
        useMaterial3: true,
      ),
      home: !auth.isLoggedIn
          ? (server == null ? const ConnectPage() : const LoginPage())
          : const HomePage(),
    );
  }
}
