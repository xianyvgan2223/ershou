import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../data/models.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/item_tile.dart';
import 'item_detail_page.dart';
import 'publish_page.dart';

class MyItemsPage extends ConsumerStatefulWidget {
  const MyItemsPage({super.key});

  @override
  ConsumerState<MyItemsPage> createState() => _MyItemsPageState();
}

class _MyItemsPageState extends ConsumerState<MyItemsPage> {
  List<Item> _items = const [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final page = await ref.read(itemWriteRepositoryProvider).mine();
      if (!mounted) return;
      setState(() {
        _items = page.records;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  Future<void> _offline(Item item) async {    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('下架商品'),
        content: Text('确定下架「' + item.title + '」吗？下架后买家看不到。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('再想想'),
          ),
          TextButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('下架', style: TextStyle(color: AppColors.price)),
          ),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await ref.read(itemWriteRepositoryProvider).offline(item.id);
      if (!mounted) return;
      showToast(context, '已下架');
      await _load();
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    } catch (e) {
      if (mounted) showToast(context, '下架失败：' + e.toString());
    }
  }

  /// 重新上架：已下架 / 已过期 重挂满 7 天，不用重填标题图片
  Future<void> _relist(Item item) async {
    try {
      await ref.read(itemWriteRepositoryProvider).relist(item.id);
      if (!mounted) return;
      showToast(context, '已重新上架，有效期重新开始计时');
      await _load();
    } on ApiException catch (e) {
      if (mounted) showToast(context, e.message);
    } catch (e) {
      if (mounted) showToast(context, '重新上架失败：$e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(title: const Text('我的发布')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _items.isEmpty
              ? EmptyState(
                  icon: Icons.sell_outlined,
                  message: _error ?? '还没有发布过东西\n点底部中间的加号发布第一件闲置',
                  onRetry: _load,
                )
              : RefreshIndicator(
                  onRefresh: _load,
                  child: ListView.separated(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    itemCount: _items.length,
                    separatorBuilder: (_, __) => const SizedBox(height: 6),
                    itemBuilder: (_, i) {
                      final item = _items[i];
                      return ItemTile(
                        item: item,
                        onTap: () => Navigator.of(context)
                            .push(MaterialPageRoute<void>(
                              builder: (_) => ItemDetailPage(itemId: item.id),
                            ))
                            .then((_) => _load()),
                        trailing: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.end,
                          children: [
                            // 已售出的不让改（后端也会拦），其余都能编辑标题和价格
                            if (item.onSale || item.statusText == '已下架')
                              TextButton(
                                onPressed: () async {
                                  final saved = await Navigator.of(context).push<bool>(
                                    MaterialPageRoute<bool>(
                                      builder: (_) => PublishPage(editing: item),
                                    ),
                                  );
                                  if (saved == true) await _load();
                                },
                                style: TextButton.styleFrom(
                                  padding: const EdgeInsets.symmetric(horizontal: 6),
                                  minimumSize: const Size(0, 30),
                                ),
                                child: const Text('编辑',
                                    style: TextStyle(fontSize: 12.5)),
                              ),
                            if (item.onSale)
                              TextButton(
                                onPressed: () => _offline(item),
                                style: TextButton.styleFrom(
                                  padding: const EdgeInsets.symmetric(horizontal: 6),
                                  minimumSize: const Size(0, 30),
                                  foregroundColor: AppColors.price,
                                ),
                                child: const Text('下架',
                                    style: TextStyle(fontSize: 12.5)),
                              )
                            // 已下架 / 已过期：一键重新挂满 7 天（后端不重过机审、不占发布额度）
                            else if (item.canRelist)
                              TextButton(
                                onPressed: () => _relist(item),
                                style: TextButton.styleFrom(
                                  padding: const EdgeInsets.symmetric(horizontal: 6),
                                  minimumSize: const Size(0, 30),
                                  foregroundColor: AppColors.brandDark,
                                ),
                                child: const Text('重新上架',
                                    style: TextStyle(fontSize: 12.5)),
                              )
                            else if (!item.onSale && item.statusText != '已下架')
                              TagChip(text: item.statusText.isEmpty ? '已售出' : item.statusText),
                            // 有效期倒计时：只给"还在卖"的商品看
                            if (item.onSale && item.daysLeft != null) ...[
                              const SizedBox(height: 2),
                              Text(
                                item.daysLeft == 0
                                    ? '即将自动下架'
                                    : '还剩 ${item.daysLeft} 天自动下架',
                                style: TextStyle(
                                  fontSize: 10.5,
                                  color: (item.daysLeft ?? 9) <= 1
                                      ? AppColors.price
                                      : AppColors.inkFaint,
                                ),
                              ),
                            ],
                          ],
                        ),
                      );
                    },
                  ),
                ),
    );
  }
}