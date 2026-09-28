import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../data/models.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/item_tile.dart';
import 'item_detail_page.dart';

class FavoritesPage extends ConsumerStatefulWidget {
  const FavoritesPage({super.key});

  @override
  ConsumerState<FavoritesPage> createState() => _FavoritesPageState();
}

class _FavoritesPageState extends ConsumerState<FavoritesPage> {
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
      final page = await ref.read(itemWriteRepositoryProvider).favorites();
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

  Future<void> _unfavorite(Item item) async {
    try {
      await ref.read(itemWriteRepositoryProvider).removeFavorite(item.id);
      if (!mounted) return;
      setState(() {
        _items = _items.where((i) => i.id != item.id).toList();
      });
      showToast(context, '已取消收藏');
    } catch (e) {
      if (mounted) showToast(context, '操作失败：' + e.toString());
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(title: const Text('我的收藏')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _items.isEmpty
              ? EmptyState(
                  icon: Icons.favorite_border,
                  message: _error ?? '还没有收藏的商品，在商品详情页点收藏试试',
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
                        trailing: IconButton(
                          onPressed: () => _unfavorite(item),
                          icon: const Icon(Icons.favorite,
                              color: AppColors.price, size: 20),
                          tooltip: '取消收藏',
                        ),
                      );
                    },
                  ),
                ),
    );
  }
}
