import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../core/providers.dart';
import '../../data/api_repository2.dart';
import '../../data/models2.dart';
import '../theme/app_theme.dart';

/// 地图选点页，两种用法（同一套界面，避免两份重复代码）：
///
/// 1. **新建点位**（发布页「地图上选」）：点地图 → 识别地名 → `POST /trade-points` 建新点；
/// 2. **选已有点位**（私聊页「改面交地点」）：点地图上的气泡直接选现成的点位，也能顺手新建。
///
/// 两种用法都 `pop` 出一个 `MapPoint`，调用方按 `id` 用即可。
class PointPickerPage extends ConsumerStatefulWidget {
  const PointPickerPage({
    super.key,
    this.campusId,
    this.pickExisting = false,
    this.title,
  });

  final int? campusId;

  /// true = 允许"点气泡选已有点位"（私聊改地址用）；
  /// false = 只新建（发布页用，那里要的就是一个精确的新地址）
  final bool pickExisting;

  final String? title;

  @override
  ConsumerState<PointPickerPage> createState() => _PointPickerPageState();
}

class _PointPickerPageState extends ConsumerState<PointPickerPage> {
  WebViewController? _web;
  final _name = TextEditingController();
  final _guide = TextEditingController();
  double? _lng;
  double? _lat;
  int _pointType = 9;
  List<GeoCandidate> _candidates = const [];
  bool _locating = false;
  bool _saving = false;
  String? _error;
  /// 用户是否手动改过名称（改过就不再被自动识别覆盖）
  bool _nameTouched = false;
  /// 地图上已选中的现成点位（pickExisting 模式下点气泡得到）
  MapPoint? _pickedExisting;
  /// 地图上的点位数据，按 id 索引（点气泡时取出精确点位）
  Map<int, MapPoint> _pointsById = const {};

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _init());
  }

  @override
  void dispose() {
    _name.dispose();
    _guide.dispose();
    super.dispose();
  }

  Future<void> _init() async {
    // 先拿点位（选已有点位时要用），再建 WebView：顺序反了页面就收不到合并数据
    await _loadPoints();
    if (!mounted) return;
    _initWeb();
  }

  Future<void> _loadPoints() async {
    try {
      final data = await ref.read(mapRepositoryProvider).points(
            campusId: widget.campusId ?? MapRepository.defaultCampusId,
          );
      if (!mounted) return;
      setState(() {
        _pointsById = {for (final p in data.points) p.id: p};
      });
    } catch (_) {
      // 拿不到点位不影响"自己点一个位置新建"
    }
  }

  void _initWeb() {
    final repo = ref.read(mapRepositoryProvider);
    final controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0xFFEEF3F0))
      ..addJavaScriptChannel('FlutterBridge', onMessageReceived: _onBridge)
      ..loadRequest(Uri.parse(repo.amapPageUrl(
        campusId: widget.campusId ?? MapRepository.defaultCampusId,
        pickable: true,
        // 让页面点地图时顺手做逆地理编码（用页面里已有的 JS API，不需要 Web 服务 key）
        wantRegeo: true,
      )));
    setState(() => _web = controller);
  }

  void _onBridge(JavaScriptMessage message) {
    Map<String, dynamic> payload;
    try {
      payload = (jsonDecode(message.message) as Map).cast<String, dynamic>();
    } catch (_) {
      return;
    }
    final type = payload['type'];
    // 点已有气泡：直接选中那个点位（只在"选已有点位"模式下有意义）
    if (type == 'select') {
      if (!widget.pickExisting) return;
      final id = payload['pointId'];
      if (id is! int) return;
      final hit = _pointsById[id];
      if (hit == null) return;
      setState(() {
        _pickedExisting = hit;
        // 选已有的就把"新建"的输入清掉，避免两种状态混在一起
        _lng = null;
        _lat = null;
        _name.text = '';
        _candidates = const [];
        _error = null;
      });
      return;
    }
    if (type != 'pick') return;
    final lng = (payload['lng'] as num?)?.toDouble();
    final lat = (payload['lat'] as num?)?.toDouble();
    if (lng == null || lat == null) return;
    // 候选地名由页面里的 AMap.Geocoder 一起回传（不用再打服务端）
    final raw = payload['candidates'];
    final candidates = raw is List
        ? raw
            .whereType<Map>()
            .map((e) => GeoCandidate.fromJson(e.cast<String, dynamic>()))
            .toList()
        : <GeoCandidate>[];
    _onPicked(lng, lat, candidates);
  }

  /// 点地图空白处：准备**新建**一个点位（候选地名已经跟着回传）
  Future<void> _onPicked(double lng, double lat, List<GeoCandidate> candidates) async {
    setState(() {
      _lng = lng;
      _lat = lat;
      _candidates = candidates;
      _locating = false;
      _error = null;
      _pickedExisting = null; // 从"选已有"切回"新建"
      // 没手填过就用最近的一个候选自动填上，用户可随时改
      if ((!_nameTouched || _name.text.trim().isEmpty) && candidates.isNotEmpty) {
        _name.text = candidates.first.name;
        _nameTouched = false;
      }
    });
    // 页面没给出候选（反查失败）时，再试一次服务端兜底接口（配了 Web 服务 key 才有结果）
    if (candidates.isEmpty && mounted) {
      setState(() => _locating = true);
      try {
        final list = await ref.read(mapRepositoryProvider).regeoCandidates(lng: lng, lat: lat);
        if (mounted && list.isNotEmpty) {
          setState(() {
            _candidates = list;
            if (!_nameTouched || _name.text.trim().isEmpty) _name.text = list.first.name;
          });
        }
      } catch (_) {
        // 反查失败不影响选点，用户自己填名字即可
      } finally {
        if (mounted) setState(() => _locating = false);
      }
    }
  }

  Future<void> _save() async {
    final name = _name.text.trim();
    if (_lng == null || _lat == null) {
      setState(() => _error = '先在地图上点一下位置');
      return;
    }
    if (name.length < 2) {
      setState(() => _error = '名称至少 2 个字');
      return;
    }
    if (name.length > 30) {
      setState(() => _error = '名称最多 30 个字（当前 ${name.length}）');
      return;
    }
    setState(() {
      _saving = true;
      _error = null;
    });
    try {
      final point = await ref.read(mapRepositoryProvider).createPoint(
            name: name,
            lng: _lng!,
            lat: _lat!,
            pointType: _pointType,
            guideText: _guide.text.trim(),
            campusId: widget.campusId ?? MapRepository.defaultCampusId,
          );
      if (!mounted) return;
      Navigator.of(context).pop(point);
    } catch (e) {
      if (mounted) {
        setState(() {
          _saving = false;
          _error = e.toString();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final existing = _pickedExisting;
    final pickedNew = _lng != null && _lat != null;
    return Scaffold(
      backgroundColor: AppColors.bg,
      appBar: AppBar(
        title: Text(widget.title ?? (widget.pickExisting ? '选面交地点' : '在地图上选点')),
        actions: [
          TextButton(
            onPressed: _saving || (!pickedNew && existing == null)
                ? null
                : () => existing != null ? Navigator.of(context).pop(existing) : _save(),
            child: _saving
                ? const SizedBox(
                    width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                : Text(existing != null ? '就用这个' : '添加'),
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Stack(
              children: [
                Positioned.fill(
                  child: _web == null
                      ? const Center(child: CircularProgressIndicator())
                      : WebViewWidget(controller: _web!),
                ),
                // 顶部提示：一句话说清怎么用
                Positioned(
                  left: 12,
                  right: 12,
                  top: 10,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
                    decoration: BoxDecoration(
                      color: Colors.black.withValues(alpha: 0.62),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text(
                      widget.pickExisting
                          ? '点地图上的气泡选个现成的，或点空白处新建一个'
                          : '点击地图上你要面交的位置',
                      textAlign: TextAlign.center,
                      style: const TextStyle(color: Colors.white, fontSize: 12.5),
                    ),
                  ),
                ),
              ],
            ),
          ),
          _sheet(existing, pickedNew),
        ],
      ),
    );
  }

  /// 底部表单：选中现成点位 → 一行确认；点空白处 → 新建表单
  Widget _sheet(MapPoint? existing, bool pickedNew) {
    return Container(
      color: Colors.white,
      padding: EdgeInsets.only(
        left: 16,
        right: 16,
        top: 12,
        bottom: MediaQuery.of(context).padding.bottom + 12,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 选中了现成点位：只显示它，点右上角「就用这个」即可
          if (existing != null) ...[
            Row(
              children: [
                const Icon(Icons.place, size: 17, color: AppColors.brandDark),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    existing.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                        fontSize: 14.5, fontWeight: FontWeight.w700, color: AppColors.brandDark),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(
              [
                MapPoint.typeLabel(existing.type),
                if (existing.itemCount > 0) '${existing.itemCount} 件在售' else '暂无在售',
                if (existing.guideText.isNotEmpty) existing.guideText,
              ].join(' · '),
              style: const TextStyle(fontSize: 12, color: AppColors.inkSoft, height: 1.5),
            ),
          ] else if (!pickedNew)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 14),
              child: Text(
                widget.pickExisting
                    ? '点地图上的气泡可以选现成的点位；点空白处则新建一个。'
                    : '还没选位置。点上面的地图任意位置即可，能自动识别出附近的地名。',
                style: const TextStyle(fontSize: 12.5, color: AppColors.inkSoft, height: 1.6),
              ),
            )
          else ...[
            Row(
              children: [
                const Icon(Icons.place, size: 15, color: AppColors.brandDark),
                const SizedBox(width: 4),
                Text(
                  '${_lng!.toStringAsFixed(6)}, ${_lat!.toStringAsFixed(6)}',
                  style: const TextStyle(fontSize: 11.5, color: AppColors.inkFaint),
                ),
                const Spacer(),
                if (_locating)
                  const SizedBox(
                      width: 12, height: 12, child: CircularProgressIndicator(strokeWidth: 2)),
              ],
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _name,
              maxLength: 30,
              onChanged: (_) => _nameTouched = true,
              decoration: const InputDecoration(
                labelText: '地点名称',
                hintText: '自己填也行，例如：相思湖 / 清苑11栋',
                counterText: '',
                isDense: true,
              ),
            ),
            if (_candidates.isNotEmpty) ...[
              const SizedBox(height: 6),
              SizedBox(
                height: 34,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: _candidates.length,
                  separatorBuilder: (_, __) => const SizedBox(width: 6),
                  itemBuilder: (context, i) {
                    final c = _candidates[i];
                    final on = c.name == _name.text.trim();
                    return GestureDetector(
                      onTap: () => setState(() {
                        _name.text = c.name;
                        _nameTouched = true;
                      }),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
                        decoration: BoxDecoration(
                          color: on ? AppColors.brandSoft : AppColors.bg,
                          borderRadius: BorderRadius.circular(AppRadius.chip),
                          border: Border.all(
                              color: on ? AppColors.brand : Colors.transparent),
                        ),
                        child: Text(
                          c.name,
                          style: TextStyle(
                            fontSize: 11.5,
                            color: on ? AppColors.brandDark : AppColors.inkSoft,
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _guide,
                    maxLength: 60,
                    decoration: const InputDecoration(
                      labelText: '指引说明（选填）',
                      hintText: '例如：从东门进直走 100 米',
                      counterText: '',
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                _typePicker(),
              ],
            ),
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!,
                  style: const TextStyle(fontSize: 12, color: AppColors.price)),
            ],
            const SizedBox(height: 4),
            const Text(
              '新增的地点无需审核，买卖双方立刻可见',
              style: TextStyle(fontSize: 11, color: AppColors.inkFaint),
            ),
          ],
        ],
      ),
    );
  }

  Widget _typePicker() {
    const types = [9, 2, 4, 7, 5, 3, 1, 6];
    return PopupMenuButton<int>(
      initialValue: _pointType,
      onSelected: (v) => setState(() => _pointType = v),
      itemBuilder: (_) => [
        for (final t in types)
          PopupMenuItem(value: t, child: Text(MapPoint.typeLabel(t), style: const TextStyle(fontSize: 13))),
      ],
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
        decoration: BoxDecoration(
          color: AppColors.bg,
          borderRadius: BorderRadius.circular(AppRadius.chip),
        ),
        child: Row(
          children: [
            Text(MapPoint.typeLabel(_pointType),
                style: const TextStyle(fontSize: 12.5, color: AppColors.inkSoft)),
            const Icon(Icons.expand_more, size: 16, color: AppColors.inkFaint),
          ],
        ),
      ),
    );
  }
}


