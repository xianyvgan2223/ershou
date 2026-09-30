/// 第二批模型：聊天 / 地图 / 面交单 / 用户主页 / 校区。
/// 字段与后端 VO 一一对应（参考 ershou-web/src/api/*.ts 的 TS 定义）。

double _num(Object? v) {
  if (v == null) return 0;
  if (v is num) return v.toDouble();
  return double.tryParse(v.toString()) ?? 0;
}

int _intOf(Object? v) =>
    v is num ? v.toInt() : (int.tryParse(v?.toString() ?? '') ?? 0);

int? _intOrNull(Object? v) => v == null ? null : _intOf(v);

String _str(Object? v) => v?.toString() ?? '';

class ChatSession {
  const ChatSession({
    required this.id,
    this.peerId = 0,
    this.peerName = '',
    this.peerAvatar = '',
    this.peerCreditScore = 0,
    this.peerOnline = false,
    this.itemId,
    this.itemTitle = '',
    this.itemCover = '',
    this.itemPrice = 0,
    this.itemStatusText = '',
    this.myRole = '',
    this.blockedByMe = false,
    this.blockedByPeer = false,
    this.canSend = true,
    this.lastMsgText = '',
    this.lastMsgAt = '',
    this.unread = 0,
  });

  final int id;
  final int peerId;
  final String peerName;
  final String peerAvatar;
  final int peerCreditScore;
  final bool peerOnline;
  final int? itemId;
  final String itemTitle;
  final String itemCover;
  final double itemPrice;
  final String itemStatusText;
  final String myRole;
  final bool blockedByMe;
  final bool blockedByPeer;
  final bool canSend;
  final String lastMsgText;
  final String lastMsgAt;
  final int unread;

  bool get iAmSeller => myRole == 'seller';

  factory ChatSession.fromJson(Map<String, dynamic> j) => ChatSession(
        id: _intOf(j['id']),
        peerId: _intOf(j['peerId']),
        peerName: _str(j['peerName']),
        peerAvatar: _str(j['peerAvatar']),
        peerCreditScore: _intOf(j['peerCreditScore']),
        peerOnline: j['peerOnline'] == true,
        itemId: _intOrNull(j['itemId']),
        itemTitle: _str(j['itemTitle']),
        itemCover: _str(j['itemCover']),
        itemPrice: _num(j['itemPrice']),
        itemStatusText: _str(j['itemStatusText']),
        myRole: _str(j['myRole']),
        blockedByMe: j['blockedByMe'] == true,
        blockedByPeer: j['blockedByPeer'] == true,
        canSend: j['canSend'] != false,
        lastMsgText: _str(j['lastMsgText']),
        lastMsgAt: _str(j['lastMsgAt']),
        unread: _intOf(j['unread']),
      );
}

class ChatMessage {
  const ChatMessage({
    this.id = 0,
    this.sessionId = 0,
    this.senderId = 0,
    this.msgType = 1,
    this.content = '',
    this.mine = false,
    this.recalled = false,
    this.riskWarning = '',
    this.clientMsgId = '',
    this.createdAt = '',
    this.localStatus = 'sent',
  });

  final int id;
  final int sessionId;
  final int senderId;
  final int msgType;
  final String content;
  final bool mine;
  final bool recalled;
  final String riskWarning;
  final String clientMsgId;
  final String createdAt;

  /// sending / sent / failed —— 本地态，REST 未成功前是 sending
  final String localStatus;

  bool get isImage => msgType == 2;

  ChatMessage copyWith({
    int? id,
    int? msgType,
    String? content,
    bool? mine,
    bool? recalled,
    String? riskWarning,
    String? clientMsgId,
    String? createdAt,
    String? localStatus,
  }) =>
      ChatMessage(
        id: id ?? this.id,
        sessionId: sessionId,
        senderId: senderId,
        msgType: msgType ?? this.msgType,
        content: content ?? this.content,
        mine: mine ?? this.mine,
        recalled: recalled ?? this.recalled,
        riskWarning: riskWarning ?? this.riskWarning,
        clientMsgId: clientMsgId ?? this.clientMsgId,
        createdAt: createdAt ?? this.createdAt,
        localStatus: localStatus ?? this.localStatus,
      );

  /// 服务端消息（有 id）才可能被撤回、才算已落库。
  bool get fromServer => id > 0;

  factory ChatMessage.fromJson(Map<String, dynamic> j) => ChatMessage(
        id: _intOf(j['id']),
        sessionId: _intOf(j['sessionId']),
        senderId: _intOf(j['senderId']),
        msgType: _intOf(j['msgType']) == 0 ? 1 : _intOf(j['msgType']),
        content: _str(j['content']),
        mine: j['mine'] == true,
        recalled: j['recalled'] == true,
        riskWarning: _str(j['riskWarning']),
        clientMsgId: _str(j['clientMsgId']),
        createdAt: _str(j['createdAt']),
        localStatus: 'sent',
      );
}

class MapPoint {
  const MapPoint({
    required this.id,
    this.name = '',
    this.type = 9,
    this.coordX = 0,
    this.coordY = 0,
    this.safetyScore = 0,
    this.safeTags = const [],
    this.itemCount = 0,
    this.minPrice = 0,
    this.guideText = '',
  });

  final int id;
  final String name;
  final int type;
  final double coordX;
  final double coordY;
  final int safetyScore;
  final List<String> safeTags;
  final int itemCount;
  final double minPrice;
  final String guideText;

  static String typeLabel(int type) {
    switch (type) {
      case 1:
        return '门卫室旁';
      case 2:
        return '食堂';
      case 3:
        return '广场';
      case 4:
        return '快递站';
      case 5:
        return '教学楼大厅';
      case 6:
        return '校警室旁';
      case 7:
        return '宿舍区';
      default:
        return '其他点位';
    }
  }

  factory MapPoint.fromJson(Map<String, dynamic> j) => MapPoint(
        id: _intOf(j['id']),
        name: _str(j['name']),
        type: _intOf(j['type']) == 0 ? 9 : _intOf(j['type']),
        coordX: _num(j['coordX']),
        coordY: _num(j['coordY']),
        safetyScore: _intOf(j['safetyScore']),
        safeTags: ((j['safeTags'] as List?) ?? const [])
            .map((e) => _str(e))
            .toList(),
        itemCount: _intOf(j['itemCount']),
        minPrice: _num(j['minPrice']),
        guideText: _str(j['guideText']),
      );
}

class MapData {
  const MapData({
    this.mapType = 2,
    this.imageUrl = '',
    this.width = 1000,
    this.height = 700,
    this.points = const [],
  });

  final int mapType;
  final String imageUrl;
  final double width;
  final double height;
  final List<MapPoint> points;

  factory MapData.fromJson(Map<String, dynamic> j) {
    final rawConfig = j['mapConfig'];
    final cfg = rawConfig is Map
        ? rawConfig.cast<String, dynamic>()
        : const <String, dynamic>{};
    final w = _num(cfg['width']);
    final h = _num(cfg['height']);
    return MapData(
      mapType: _intOf(j['mapType']) == 0 ? 2 : _intOf(j['mapType']),
      imageUrl: _str(cfg['imgUrl']),
      width: w == 0 ? 1000 : w,
      height: h == 0 ? 700 : h,
      points: ((j['points'] as List?) ?? const [])
          .whereType<Map>()
          .map((e) => MapPoint.fromJson(e.cast<String, dynamic>()))
          .toList(),
    );
  }
}

class MeetupOrder {
  const MeetupOrder({
    required this.id,
    this.orderNo = '',
    this.itemId = 0,
    this.itemTitle = '',
    this.itemCover = '',
    this.quantity = 1,
    this.dealPrice = 0,
    this.pointName = '',
    this.pointGuide = '',
    this.timeSlotStart = '',
    this.timeSlotEnd = '',
    this.status = 0,
    this.statusText = '',
    this.buyerId = 0,
    this.buyerName = '',
    this.sellerId = 0,
    this.sellerName = '',
    this.remark = '',
    this.createdAt = '',
  });

  final int id;
  final String orderNo;
  final int itemId;
  final String itemTitle;
  final String itemCover;
  final int quantity;
  final double dealPrice;
  final String pointName;
  final String pointGuide;
  final String timeSlotStart;
  final String timeSlotEnd;
  final int status;
  final String statusText;
  final int buyerId;
  final String buyerName;
  final int sellerId;
  final String sellerName;
  final String remark;
  final String createdAt;

  factory MeetupOrder.fromJson(Map<String, dynamic> j) => MeetupOrder(
        id: _intOf(j['id']),
        orderNo: _str(j['orderNo']),
        itemId: _intOf(j['itemId']),
        itemTitle: _str(j['itemTitle']),
        itemCover: _str(j['itemCover']),
        quantity: _intOf(j['quantity']) == 0 ? 1 : _intOf(j['quantity']),
        dealPrice: _num(j['dealPrice']),
        pointName: _str(j['pointName']),
        pointGuide: _str(j['pointGuide']),
        timeSlotStart: _str(j['timeSlotStart']),
        timeSlotEnd: _str(j['timeSlotEnd']),
        status: _intOf(j['status']),
        statusText: _str(j['statusText']),
        buyerId: _intOf(j['buyerId']),
        buyerName: _str(j['buyerName']),
        sellerId: _intOf(j['sellerId']),
        sellerName: _str(j['sellerName']),
        remark: _str(j['remark']),
        createdAt: _str(j['createdAt']),
      );
}

class PublicProfile {
  const PublicProfile({
    required this.id,
    this.nickname = '',
    this.avatar = '',
    this.creditScore = 0,
    this.shopName = '',
    this.schoolName = '',
    this.campusName = '',
    this.onSaleCount = 0,
    this.blocked = false,
    this.blockedByPeer = false,
    this.self = false,
    this.createdAt = '',
  });

  final int id;
  final String nickname;
  final String avatar;
  final int creditScore;
  final String shopName;
  final String schoolName;
  final String campusName;
  final int onSaleCount;
  final bool blocked;
  final bool blockedByPeer;
  final bool self;
  final String createdAt;

  factory PublicProfile.fromJson(Map<String, dynamic> j) => PublicProfile(
        id: _intOf(j['id']),
        nickname: _str(j['nickname']),
        avatar: _str(j['avatar']),
        creditScore: _intOf(j['creditScore']),
        shopName: _str(j['shopName']),
        schoolName: _str(j['schoolName']),
        campusName: _str(j['campusName']),
        onSaleCount: _intOf(j['onSaleCount']),
        blocked: j['blocked'] == true,
        blockedByPeer: j['blockedByPeer'] == true,
        self: j['self'] == true,
        createdAt: _str(j['createdAt']),
      );
}

class CampusInfo {
  const CampusInfo({required this.id, this.schoolId = 0, this.name = ''});

  final int id;
  final int schoolId;
  final String name;

  factory CampusInfo.fromJson(Map<String, dynamic> j) => CampusInfo(
        id: _intOf(j['id']),
        schoolId: _intOf(j['schoolId']),
        name: _str(j['name']),
      );
}
