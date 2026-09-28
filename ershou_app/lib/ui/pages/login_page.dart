import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';

class LoginPage extends ConsumerStatefulWidget {
  const LoginPage({super.key});

  @override
  ConsumerState<LoginPage> createState() => _LoginPageState();
}

class _LoginPageState extends ConsumerState<LoginPage> {
  final _phone = TextEditingController(text: '13800000001');
  final _code = TextEditingController(text: '123456');
  int _countdown = 0;

  @override
  void dispose() {
    _phone.dispose();
    _code.dispose();
    super.dispose();
  }

  Future<void> _sendCode() async {
    final phone = _phone.text.trim();
    if (!RegExp(r'^1[3-9]\d{9}$').hasMatch(phone)) {
      _toast('手机号格式不对');
      return;
    }
    try {
      await ref.read(authRepositoryProvider).sendSmsCode(phone);
      _toast('验证码已发送（开发环境固定 123456）');
      setState(() => _countdown = 60);
      while (_countdown > 0 && mounted) {
        await Future<void>.delayed(const Duration(seconds: 1));
        if (!mounted) return;
        setState(() => _countdown--);
      }
    } catch (e) {
      _toast('发送失败：' + e.toString());
    }
  }

  Future<void> _login() async {
    final ok = await ref.read(authProvider.notifier).login(
          phone: _phone.text.trim(),
          code: _code.text.trim(),
        );
    if (!mounted) return;
    if (!ok) {
      final error = ref.read(authProvider).error;
      _toast(error == null || error.isEmpty ? '登录失败' : error);
    }
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final server = ref.watch(activeServerProvider);
    final auth = ref.watch(authProvider);
    return Scaffold(
      appBar: AppBar(
        title: const Text('登录'),
        actions: [
          TextButton.icon(
            onPressed: () async {
              await ref.read(activeServerProvider.notifier).forget();
            },
            icon: const Icon(Icons.settings_ethernet),
            label: const Text('换服务器'),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          Text(
            '当前服务器：' + (server?.origin ?? '未配置'),
            style: Theme.of(context).textTheme.bodySmall,
          ),
          const SizedBox(height: 24),
          TextField(
            controller: _phone,
            keyboardType: TextInputType.phone,
            decoration: const InputDecoration(
              labelText: '手机号',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _code,
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: '验证码',
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              SizedBox(
                width: 120,
                child: OutlinedButton(
                  onPressed: _countdown > 0 ? null : _sendCode,
                  child: Text(_countdown > 0 ? _countdown.toString() + 's' : '获取验证码'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: auth.loading ? null : _login,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: auth.loading
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('登录'),
            ),
          ),
          const SizedBox(height: 12),
          Text(
            '开发环境短信是 mock 的：任意合法手机号 + 123456 即可登录。',
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
      ),
    );
  }
}
