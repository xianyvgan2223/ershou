import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/providers.dart';
import '../../core/utils/formatters.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';
import '../widgets/common.dart';
import 'items_by_point_page.dart';

/// 校内地图：后端给的是自绘底图 + 像素坐标（CRS.Simple），
/// 所以这里用 InteractiveViewer + Stack 定位，不引第三方地图库。
class MapPage extends ConsumerStatefulWidget {
  const MapPage({super.key});

  @override
  ConsumerState<MapPage> createState() => _MapPageState();
}

class _MapPageState extends ConsumerState<MapPage> {
  final _controller = TransformationController();
  MapData _data = const MapData();
  MapPoint? _selected;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final data = await ref.read(mapRepositoryProvider).points();
      if (!mounted) return;
      setState(() {
        _data = data;
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
      appBar: AppBar(
        title: const Text('校内地图'),
        actions: [
          IconButton(
            onPressed: () => _controller.value = Matrix4.identity(),
            icon: const Icon(Icons.center_focus_strong_outlined, size: 20),
            tooltip: '复位',
          ),
          IconButton(
            onPressed: _load,
            icon: const Icon(Icons.refresh, size: 20),
            tooltip: '刷新',
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? EmptyState(
                  icon: Icons.map_outlined,
                  message: _error!,
                  onRetry: _load,
                )
              : Column(
                  children: [
                    Expanded(child: _mapArea()),
                    _bottomPanel(),
                  ],
                ),
    );
  }
  
  Widget _mapArea() {
    return LayoutBuilder(
      builder: (context, box) {
        final w = _data.width;
        final h = _data.height;
        // 等比缩放到容器内
        final scale = math.min(box.maxWidth / w, box.maxHeight / h);
        return ClipRect(
          child: InteractiveViewer(
            transformationController: _controller,
            minScale: 0.8,
            maxScale: 4,
            boundaryMargin: const EdgeInsets.all(80),
            child: Center(
              child: SizedBox(
                width: w * scale,
                height: h * scale,
                child: Stack(
                  children: [
                    Positioned.fill(
                      child: _data.imageUrl.isEmpty
                          ? const _GridBackdrop()
                          : _MapImage(url: _data.imageUrl),
                    ),
                    for (final p in _data.points)
                      Positioned(
                        left: p.coordX * scale - 26,
                        top: p.coordY * scale - 34,
                        child: _marker(p),
                      ),
                  ],
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _marker(MapPoint p) {
    final selected = _selected?.id == p.id;
    return GestureDetector(
      onTap: () => setState(() => _selected = p),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
            decoration: BoxDecoration(
              color: selected ? AppColors.price : Colors.white,
              borderRadius: BorderRadius.circular(999),
              boxShadow: AppTheme.softShadow,
              border: Border.all(
                color: selected ? AppColors.price : AppColors.line,
              ),
            ),
            child: Text(
              p.itemCount > 0 ? '￥' + Fmt.price(p.minPrice) + ' · ' + p.itemCount.toString() + '件' : '暂无在售',
              style: TextStyle(
                fontSize: 10.5,
                fontWeight: FontWeight.w600,
                color: selected ? Colors.white : AppColors.ink,
              ),
            ),
          ),
          Container(
            width: 2,
            height: 8,
            color: selected ? AppColors.price : AppColors.inkFaint,
          ),
          Icon(Icons.place, size: 14, color: selected ? AppColors.price : AppColors.inkFaint),
        ],
      ),
    );
  }

  Widget _bottomPanel() {
    final p = _selected;
    if (p == null) {
      return Container(
        width: double.infinity,
        color: Colors.white,
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SectionHeader(title: '面交点', subtitle: '点地图上的标记看详情'),
            Text(
              _data.points.isEmpty
                  ? '这个校区还没有配置点位'
                  : '共 ' + _data.points.length.toString() + ' 个点位，' +
                      '带价格的是有在售商品的面交点',
              style: const TextStyle(fontSize: 12.5, color: AppColors.inkSoft),
            ),
          ],
        ),
      );
    }
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        color: Colors.white,
        border: Border(top: BorderSide(color: AppColors.line)),
      ),
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  p.name,
                  style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                ),
              ),
              IconButton(
                onPressed: () => setState(() => _selected = null),
                icon: const Icon(Icons.close, size: 18),
                visualDensity: VisualDensity.compact,
              ),
            ],
          ),
          const SizedBox(height: 4),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              TagChip(
                text: MapPoint.typeLabel(p.type),
                color: AppColors.brandDark,
                background: AppColors.brandSoft,
              ),
              TagChip(text: '安全分 ' + p.safetyScore.toString()),
              for (final tag in p.safeTags) TagChip(text: tag),
              if (p.itemCount > 0)
                TagChip(
                  text: p.itemCount.toString() + ' 件在售',
                  color: AppColors.price,
                  background: const Color(0xFFFFF0EC),
                ),
            ],
          ),
          if (p.guideText.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              p.guideText,
              style: const TextStyle(fontSize: 12.5, height: 1.6, color: AppColors.inkSoft),
            ),
          ],
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: p.itemCount == 0
                  ? null
                  : () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => ItemsByPointPage(point: p),
                        ),
                      ),
              icon: const Icon(Icons.storefront_outlined, size: 18),
              label: Text(p.itemCount == 0 ? '这个点位暂无在售' : '看这个点位的商品'),
            ),
          ),
        ],
      ),
    );
  }
}

/// 自绘底图：没有图时用示意网格 + 校园色块，避免一片空白。
class _GridBackdrop extends StatelessWidget {
  const _GridBackdrop();

  @override
  Widget build(BuildContext context) {
    return CustomPaint(painter: _GridPainter());
  }
}

class _GridPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(
      Offset.zero & size,
      Paint()..color = const Color(0xFFEAF3EC),
    );
    final line = Paint()
      ..color = const Color(0xFFD3E4D8)
      ..strokeWidth = 1;
    const step = 40.0;
    for (var x = 0.0; x < size.width; x += step) {
      canvas.drawLine(Offset(x, 0), Offset(x, size.height), line);
    }
    for (var y = 0.0; y < size.height; y += step) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), line);
    }
    // 几条示意道路
    final road = Paint()
      ..color = Colors.white
      ..strokeWidth = 14;
    canvas.drawLine(
      Offset(0, size.height * 0.45),
      Offset(size.width, size.height * 0.45),
      road,
    );
    canvas.drawLine(
      Offset(size.width * 0.5, 0),
      Offset(size.width * 0.5, size.height),
      road,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}

class _MapImage extends ConsumerWidget {
  const _MapImage({required this.url});

  final String url;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final resolved = ref.read(itemRepositoryProvider).imageUrl(url);
    return Image.network(
      resolved,
      fit: BoxFit.cover,
      errorBuilder: (_, __, ___) => const _GridBackdrop(),
    );
  }
}