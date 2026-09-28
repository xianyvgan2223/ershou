import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/network/api_client.dart';
import '../../core/providers.dart';
import '../../data/models.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import '../widgets/item_card.dart';
import 'item_detail_page.dart';

/// 某个面交点在售的商品（地图 → 点位 → 商品）。
///
/// 商品列表接口暂时没有 pointId 过滤参数，所以这里拉一批再按 preferPointName 过滤。
class ItemsByPointPage extends ConsumerStatefulWidget {
  const ItemsByPointPage({super.key, required this.point});

  final MapPoint point;

  @override
  ConsumerState<ItemsByPointPage> createState() => _ItemsByPointPageState();
}

class _ItemsByPointPageState extends ConsumerState<ItemsByPointPage> {
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
      final page = await ref.read(itemRepositoryProvider).page(pageSize: 50);
      if (!mounted) return;
      final matched = page.records
          .where((i) => i.preferPointName == widget.point.name)
          .toList();
      setState(() {
        _items = matched;
        _loading = false;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(title: Text(widget.point.name)),
      body: _loading
          ? const SkeletonGrid(count: 4)
          : _items.isEmpty
              ? EmptyState(
                  icon: Icons.inventory_2_outlined,
                  message: _error ?? '这个点位暂时没有在售商品',
                  onRetry: _load,
                )
              : RefreshIndicator(
                  onRefresh: _load,
                  child: GridView.builder(
                    padding: const EdgeInsets.all(AppSpacing.page),
                    gridDelegate:
                        const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 2,
                      mainAxisSpacing: AppSpacing.gap,
                      crossAxisSpacing: AppSpacing.gap,
                      childAspectRatio: 0.60,
                    ),
                    itemCount: _items.length,
                    itemBuilder: (_, i) => ItemCard(
                      item: _items[i],
                      onTap: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => ItemDetailPage(itemId: _items[i].id),
                        ),
                      ),
                    ),
                  ),
                ),
    );
  }
}
