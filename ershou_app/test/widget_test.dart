// 默认模板的计数器测试已经用不上了，这里只留一个最小的 smoke 断言。
import 'package:ershou_app/core/config/server_config.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('ServerConfig 会拼出正确的接口地址', () {
    const config = ServerConfig(host: '192.168.1.7', port: 8080);
    expect(config.origin, 'http://192.168.1.7:8080');
    expect(config.apiBase, 'http://192.168.1.7:8080/api/v1');
  });

  test('图片地址只换主机名，端口必须保留（MinIO 在 9010）', () {
    const config = ServerConfig(host: '10.34.29.94', port: 8080);
    expect(
      config.resolveAssetUrl('http://127.0.0.1:9010/ershou/a.jpg'),
      'http://10.34.29.94:9010/ershou/a.jpg',
    );
    // 配了真实 IP 时，127.0.0.1 必须被换掉（手机上的 127.0.0.1 是手机自己）
    const lanConfig = ServerConfig(host: '192.168.17.112', port: 8080);
    expect(
      lanConfig.resolveAssetUrl('http://127.0.0.1:9010/ershou/a.jpg'),
      'http://192.168.17.112:9010/ershou/a.jpg',
    );

    // 已经是别的可达主机（比如后端直接返回了真实 IP）就不用动它
    expect(
      config.resolveAssetUrl('http://192.168.17.112:9010/ershou/a.jpg'),
      'http://192.168.17.112:9010/ershou/a.jpg',
    );
    expect(config.resolveAssetUrl('/static/upload/x.png'),
        'http://10.34.29.94:8080/static/upload/x.png');
    expect(config.resolveAssetUrl('https://cdn.example.com/a.jpg'),
        'https://cdn.example.com/a.jpg');
  });
}
