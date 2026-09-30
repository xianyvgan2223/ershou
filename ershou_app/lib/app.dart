import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/app_lifecycle.dart';
import 'core/providers.dart';
import 'ui/pages/connect_page.dart';
import 'ui/pages/login_page.dart';
import 'ui/pages/shell_page.dart';
import 'ui/theme/app_theme.dart';

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
      theme: AppTheme.light(),
      // 全局字号缩一档：所有页面里 setState 之外的字号都跟着变小，
      // 不用去几十个文件里逐个改 fontSize。系统字体放大设置仍然生效（相乘）。
      builder: (context, child) {
        final media = MediaQuery.of(context);
        return MediaQuery(
          data: media.copyWith(
            // 先取当前缩放的数值（保留用户的系统字体设置），再乘 0.9 收一档
            textScaler: TextScaler.linear(media.textScaler.scale(1.0) * 0.9),
          ),
          child: child ?? const SizedBox.shrink(),
        );
      },
      // ChatConnectionKeeper：全局长连接保活 + 前台重连（登录前后都挂）
      home: ChatConnectionKeeper(
        child: !auth.isLoggedIn
            ? (server == null ? const ConnectPage() : const LoginPage())
            : const ShellPage(),
      ),
    );
  }
}
