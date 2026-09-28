import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'connect_page.dart';

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _phone = TextEditingController();
  final _code = TextEditingController();
  int _countdown = 0;
  String? _error;
  bool _agree = true;

  @override
  void dispose() {
    _phone.dispose();
    _code.dispose();
    super.dispose();
  }

  bool get _phoneValid => RegExp(r'^1[3-9]\d{9}$').hasMatch(_phone.text.trim());

  Future<void> _sendCode() async {
    if (!_phoneValid) {
      setState(() => _error = '手机号格式不对（11 位，1 开头）');
      return;
    }
    setState(() => _error = null);
    try {
      await ref.read(authRepositoryProvider).sendSmsCode(_phone.text.trim());
      if (!mounted) return;
      showToast(context, '验证码已发送，开发环境固定 123456');
      setState(() => _countdown = 60);
      while (_countdown > 0 && mounted) {
        await Future<void>.delayed(const Duration(seconds: 1));
        if (!mounted) return;
        setState(() => _countdown--);
      }
    } catch (e) {
      if (!mounted) return;
      setState(() => _error = '发送失败：' + e.toString());
    }
  }

  Future<void> _login() async {
    if (!_phoneValid) {
      setState(() => _error = '手机号格式不对（11 位，1 开头）');
      return;
    }
    if (_code.text.trim().isEmpty) {
      setState(() => _error = '请填验证码（开发环境是 123456）');
      return;
    }
    if (!_agree) {
      setState(() => _error = '先勾选同意用户协议');
      return;
    }
    setState(() => _error = null);
    FocusScope.of(context).unfocus();
    final ok = await ref.read(authProvider.notifier).login(
          phone: _phone.text.trim(),
          code: _code.text.trim(),
          deviceId: _deviceId(),
        );
    if (!mounted) return;
    if (!ok) {
      final error = ref.read(authProvider).error;
      setState(() => _error = (error == null || error.isEmpty) ? '登录失败' : error);
    }
  }

  /// 设备标识：后端只拿它做风控统计，本地存一份就够，不用引入 device_info。
  String _deviceId() {
    final store = ref.read(localStoreProvider);
    final existing = store.token; // 复用已存的值占位，避免额外依赖
    final seed = existing ?? '';
    final hash = seed.hashCode.abs().toRadixString(16);
    return 'android-' + hash;
  }

  @override
  Widget build(BuildContext context) {
    final server = ref.watch(activeServerProvider);
    final auth = ref.watch(authProvider);
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          children: [
            Align(
              alignment: Alignment.centerRight,
              child: TextButton.icon(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute<void>(builder: (_) => const ConnectPage()),
                ),
                icon: const Icon(Icons.dns_outlined, size: 16),
                label: const Text('服务器', style: TextStyle(fontSize: 12)),
              ),
            ),
            const SizedBox(height: 24),
            Container(
              width: 64,
              height: 64,
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  colors: [AppColors.brand, Color(0xFF00D08A)],
                ),
                borderRadius: BorderRadius.circular(20),
              ),
              child: const Icon(Icons.storefront_rounded, color: Colors.white, size: 34),
            ),
            const SizedBox(height: 18),
            const Text(
              '校园二手',
              style: TextStyle(fontSize: 26, fontWeight: FontWeight.w700, letterSpacing: 1),
            ),
            const SizedBox(height: 6),
            const Text(
              '同学之间，把闲置流转起来',
              style: TextStyle(fontSize: 13.5, color: AppColors.inkFaint),
            ),
            const SizedBox(height: 36),
            TextField(
              controller: _phone,
              keyboardType: TextInputType.phone,
              maxLength: 11,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              onChanged: (_) => setState(() {}),
              style: const TextStyle(fontSize: 16, letterSpacing: 1),
              decoration: const InputDecoration(
                labelText: '手机号',
                hintText: '请输入 11 位手机号',
                counterText: '',
                prefixIcon: Icon(Icons.phone_iphone_rounded, color: AppColors.inkFaint),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _code,
                    keyboardType: TextInputType.number,
                    maxLength: 6,
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    onSubmitted: (_) => _login(),
                    style: const TextStyle(fontSize: 16, letterSpacing: 4),
                    decoration: const InputDecoration(
                      labelText: '验证码',
                      hintText: '6 位数字',
                      counterText: '',
                      prefixIcon: Icon(Icons.lock_outline_rounded, color: AppColors.inkFaint),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                SizedBox(
                  height: 48,
                  child: TextButton(
                    onPressed: (_countdown > 0 || !_phoneValid) ? null : _sendCode,
                    style: TextButton.styleFrom(
                      foregroundColor: AppColors.brand,
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                    ),
                    child: Text(
                      _countdown > 0 ? _countdown.toString() + 's 后重发' : '获取验证码',
                      style: const TextStyle(fontSize: 13),
                    ),
                  ),
                ),
              ],
            ),
            if (_error != null) ...[
              const SizedBox(height: 12),
              Row(
                children: [
                  const Icon(Icons.info_outline, size: 15, color: AppColors.price),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Text(
                      _error!,
                      style: const TextStyle(fontSize: 12.5, color: AppColors.price),
                    ),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 24),
            FilledButton(
              onPressed: auth.loading ? null : _login,
              child: auth.loading
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                    )
                  : const Text('登录 / 注册'),
            ),
            const SizedBox(height: 14),
            Row(
              children: [
                SizedBox(
                  width: 22,
                  height: 22,
                  child: Checkbox(
                    value: _agree,
                    activeColor: AppColors.brand,
                    onChanged: (v) => setState(() => _agree = v ?? false),
                  ),
                ),
                const SizedBox(width: 6),
                const Expanded(
                  child: Text(
                    '未实名注册，登录即表示同意校园二手用户协议',
                    style: TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 20),
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: AppColors.bg,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                children: [
                  const Icon(Icons.tips_and_updates_outlined,
                      size: 15, color: AppColors.inkFaint),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      '开发环境短信是 mock 的：任意合法手机号 + 123456 即可登录\n'
                          '当前服务器：' +
                          (server?.origin ?? '未配置'),
                      style: const TextStyle(
                        fontSize: 11.5,
                        color: AppColors.inkSoft,
                        height: 1.6,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
