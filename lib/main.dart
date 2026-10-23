import 'package:flutter/material.dart';
import 'dart:async';

import 'package:flutter/services.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Sunshine',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
        useMaterial3: true,
      ),
      home: const StopwatchPage(title: 'Sunshine'),
    );
  }
}

/// DLNA 投屏的运行统计，来自 Java 侧 DlnaStreamer.getStats()。
///
/// 这些数字是排查「画面延迟大、卡顿、音画不同步」的直接依据：
///   bitrate    真实出网码率，和编码配置对比能看出有没有被网络卡住
///   backlog    编码产出比接收端拉走多出来的数据，换算成秒。
///              这个值持续增长就说明接收端吞吐不够，画面会越来越滞后
///   clientActive 接收端是否还在持续拉流，false 说明它已经断开或卡死
class DlnaStats {
  final int bitrate;
  final int videoFrames;
  final int audioFrames;
  final int servedBytes;
  final int clients;
  final bool clientActive;
  final int backlogSeconds;

  const DlnaStats({
    required this.bitrate,
    required this.videoFrames,
    required this.audioFrames,
    required this.servedBytes,
    required this.clients,
    required this.clientActive,
    required this.backlogSeconds,
  });

  @override
  bool operator ==(Object other) =>
      other is DlnaStats &&
      other.bitrate == bitrate &&
      other.videoFrames == videoFrames &&
      other.audioFrames == audioFrames &&
      other.servedBytes == servedBytes &&
      other.clients == clients &&
      other.clientActive == clientActive &&
      other.backlogSeconds == backlogSeconds;

  @override
  int get hashCode => Object.hash(bitrate, videoFrames, audioFrames,
      servedBytes, clients, clientActive, backlogSeconds);

  String get bitrateText => bitrate >= 1000000
      ? '${(bitrate / 1000000).toStringAsFixed(2)} Mbps'
      : '${(bitrate / 1000).toStringAsFixed(0)} kbps';

  String get servedText => servedBytes >= 1048576
      ? '${(servedBytes / 1048576).toStringAsFixed(1)} MB'
      : '${(servedBytes / 1024).toStringAsFixed(0)} KB';
}

/// 画面效果预览。
///
/// 用 16:9 的编码帧模拟客户端屏幕，按源画面方向展示实际传输效果：
///   - 竖向内容（例如手机竖屏刷短剧）：等比适应，两侧黑边
///   - 横向内容（例如手机横屏看视频）：裁剪铺满，无黑边
///
/// 两种效果由服务端根据源画面方向自动选择，用户不需要手动配置；
/// 这里的「竖向 / 横向」只是预览切换，用来分别查看两种源方向下的结果。
class RotationPreview extends StatelessWidget {
  const RotationPreview({
    super.key,
    required this.mode,
    required this.landscape,
  });

  /// 0=默认，1=左转90°，2=180°，3=右转90°
  final int mode;

  /// true=预览横向源的效果，false=预览竖向源的效果
  final bool landscape;

  @override
  Widget build(BuildContext context) {
    const double frameAspect = 16 / 9;
    // 横向内容用 16:9 源，竖向内容用 9:16 源
    final double sourceAspect = landscape ? 16 / 9 : 9 / 16;

    // 旋转 90/270 后源画面的可见宽高互换
    final bool swapped = mode == 1 || mode == 3;
    final double visibleAspect = swapped ? 1 / sourceAspect : sourceAspect;

    // 与服务端 compute_fit 的 auto 规则保持一致：始终等比适应，完整可见
    final bool fill = false;
    final bool sourceIsWider = visibleAspect > frameAspect;

    final double widthFactor;
    final double heightFactor;
    if (sourceIsWider == !fill) {
      widthFactor = 1.0;
      heightFactor = frameAspect / visibleAspect;
    } else {
      widthFactor = visibleAspect / frameAspect;
      heightFactor = 1.0;
    }

    final int quarterTurns = mode == 2 ? 2 : (swapped ? 1 : 0);
    // 左转90° 与右转90° 是相反方向
    final bool counterClockwise = mode == 1;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Text('预览：', style: TextStyle(fontSize: 13)),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                _modeLabel(mode, fill),
                style: TextStyle(
                  fontSize: 13,
                  color: Theme.of(context).colorScheme.primary,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        AspectRatio(
          aspectRatio: frameAspect,
          child: Container(
            decoration: BoxDecoration(
              color: Colors.black,
              border: Border.all(color: Colors.black87),
              borderRadius: BorderRadius.circular(6),
            ),
            clipBehavior: Clip.hardEdge,
            child: Center(
              child: FractionallySizedBox(
                widthFactor: widthFactor * 0.92,
                heightFactor: heightFactor * 0.92,
                child: RotatedBox(
                  quarterTurns:
                      counterClockwise ? -quarterTurns : quarterTurns,
                  child: _phoneScreen(),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  String _modeLabel(int mode, bool fill) {
    final String base;
    switch (mode) {
      case 1:
        base = '左转90°';
        break;
      case 2:
        base = '180°（倒立）';
        break;
      case 3:
        base = '右转90°';
        break;
      default:
        base = '默认（不旋转）';
        break;
    }
    return fill ? '$base · 裁剪铺满' : '$base · 适应（留黑边）';
  }

  /// 模拟一块手机屏幕，用可辨识的图形让旋转方向一目了然
  Widget _phoneScreen() {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFFEAF2FB),
        borderRadius: BorderRadius.circular(3),
      ),
      padding: const EdgeInsets.all(3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 顶部标题条
          Container(
            height: 10,
            decoration: BoxDecoration(
              color: const Color(0xFF9BC4EA),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 4),
          // 中间的「画面」占位，带方向标识
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(2),
              ),
              child: const Center(
                child: Text(
                  'A',
                  style: TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.bold,
                    color: Color(0xFF2B4C6F),
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 4),
          // 底部按钮条
          Container(
            height: 9,
            decoration: BoxDecoration(
              color: const Color(0xFF3B6EA5),
              borderRadius: BorderRadius.circular(2),
            ),
          ),
        ],
      ),
    );
  }
}

class StopwatchPage extends StatefulWidget {
  const StopwatchPage({super.key, required this.title});

  final String title;

  @override
  State<StopwatchPage> createState() => _StopwatchPageState();
}

class _StopwatchPageState extends State<StopwatchPage> {
  String address = '';
  MethodChannel channel = MethodChannel('com.nightmare.sunshine');
  TextEditingController pinController = TextEditingController();
  // 计时器
  Timer? _timer;
  // 开始时间
  int _startTime = 0;
  // 已经流逝的时间（毫秒）
  int _elapsedTime = 0;
  // 当前状态：0-停止，1-运行，2-暂停
  int _state = 0;
  // 画面旋转模式：0=默认（不旋转），1=左转90°，2=180°，3=右转90°
  int _rotationMode = 0;
  // 预览方向：0=竖向源，1=横向源（只影响预览，实际由服务端按源方向自动判断）
  int _previewOrientation = 0;
  // 服务端是否在运行（由 native 主循环状态决定）
  bool _serverRunning = false;
  // 当前连上来的客户端数量
  int _clientCount = 0;
  // 状态轮询定时器
  Timer? _statusTimer;
  // DLNA 投屏状态
  bool _dlnaRunning = false;
  bool _dlnaScanning = false;
  String _dlnaTarget = '';
  // 音频轨状态：投屏中才有意义
  bool _dlnaHasAudio = false;
  // 没有音频时的原因（例如系统版本太低）
  String _dlnaAudioError = '';
  // 运行统计（码率、帧数、积压等），调试用
  DlnaStats? _dlnaStats;
  List<Map<String, dynamic>> _dlnaDevices = [];

  /// 运行统计里的一项：标签 + 数值，数值可标红提醒。
  Widget _statItem(String label, String value, {bool warn = false}) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('$label ',
            style: const TextStyle(fontSize: 11, color: Color(0xFF757575))),
        Text(value,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: warn ? const Color(0xFFD32F2F) : const Color(0xFF212121),
            )),
      ],
    );
  }

  // 格式化时间显示
  String _formatTime(int milliseconds) {
    int millis = milliseconds % 1000; // 完整的毫秒部分
    int seconds = (milliseconds / 1000).truncate() % 60;
    int minutes = (milliseconds / 60000).truncate() % 60;
    int hours = (milliseconds / 3600000).truncate();

    String hoursStr = hours > 0 ? '${hours.toString().padLeft(2, '0')}:' : '';
    String minutesStr = minutes.toString().padLeft(2, '0');
    String secondsStr = seconds.toString().padLeft(2, '0');
    String millisStr = millis.toString().padLeft(3, '0'); // 3位毫秒值

    return '$hoursStr$minutesStr:$secondsStr.$millisStr';
  }

  // 开始计时
  void _start() {
    _startTime = DateTime.now().millisecondsSinceEpoch - _elapsedTime;
    _timer = Timer.periodic(const Duration(milliseconds: 1), (timer) {
      // 改为每毫秒刷新一次
      setState(() {
        _elapsedTime = DateTime.now().millisecondsSinceEpoch - _startTime;
      });
    });
    setState(() {
      _state = 1;
    });
  }

  // 暂停计时
  void _pause() {
    _timer?.cancel();
    setState(() {
      _state = 2;
    });
  }

  // 停止并重置计时
  void _reset() {
    _timer?.cancel();
    setState(() {
      _state = 0;
      _elapsedTime = 0;
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    _statusTimer?.cancel();
    super.dispose();
  }

  /// 轮询服务端运行状态与客户端数量，用于界面指示灯。
  void _refreshServerStatus() {
    channel.invokeMethod('serverStatus').then((value) {
      if (value is String && mounted) {
        final parts = value.split('|');
        if (parts.length == 2) {
          final bool running = parts[0] == '1';
          final int clients = int.tryParse(parts[1]) ?? 0;
          if (running != _serverRunning || clients != _clientCount) {
            setState(() {
              _serverRunning = running;
              _clientCount = clients;
            });
          }
        }
      }
    });

    // DLNA 投屏状态同步（授权回来之后是异步启动的，靠轮询感知）
    channel.invokeMethod('dlnaStatus').then((value) {
      if (value is String && mounted) {
        // 格式：运行中|接收端名|流地址|音频状态|运行统计
        final parts = value.split('|');
        if (parts.length >= 4) {
          final bool running = parts[0] == '1';
          final String target = parts[1];
          final String audio = parts[3];
          final bool hasAudio = audio == 'a';
          final String audioError =
              audio.startsWith('e:') ? audio.substring(2) : '';

          // 运行统计：码率|视频帧|音频帧|出网字节|连接数|接收端活跃|积压秒
          DlnaStats? stats;
          if (parts.length >= 5 && parts[4].isNotEmpty) {
            final s = parts[4].split(',');
            if (s.length >= 7) {
              stats = DlnaStats(
                bitrate: int.tryParse(s[0]) ?? 0,
                videoFrames: int.tryParse(s[1]) ?? 0,
                audioFrames: int.tryParse(s[2]) ?? 0,
                servedBytes: int.tryParse(s[3]) ?? 0,
                clients: int.tryParse(s[4]) ?? 0,
                clientActive: s[5] == '1',
                backlogSeconds: int.tryParse(s[6]) ?? 0,
              );
            }
          }

          if (running != _dlnaRunning ||
              target != _dlnaTarget ||
              hasAudio != _dlnaHasAudio ||
              audioError != _dlnaAudioError ||
              stats != _dlnaStats) {
            setState(() {
              _dlnaRunning = running;
              _dlnaTarget = target;
              _dlnaHasAudio = hasAudio;
              _dlnaAudioError = audioError;
              _dlnaStats = stats;
            });
          }
        }
      }
    });
  }

  @override
  void initState() {
    super.initState();
    channel.invokeMethod('address').then((value) {
      setState(() {
        address = value;
      });
    });
    channel.invokeMethod('getRotationMode').then((value) {
      if (value is int && mounted) {
        setState(() {
          _rotationMode = value;
        });
      }
    });
    _statusTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      _refreshServerStatus();
    });
    _refreshServerStatus();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        title: Text(widget.title + ' ' + address),
      ),
      body: Padding(
        padding: EdgeInsets.symmetric(horizontal: 8),
        child: SingleChildScrollView(
          child: Column(
            children: <Widget>[
              // 秒表显示区域
              SizedBox(
                height: 110,
                child: Center(
                  child: Text(
                    _formatTime(_elapsedTime),
                    style: const TextStyle(
                      fontSize: 48,
                      fontWeight: FontWeight.bold,
                      fontFamily: 'monospace',
                    ),
                  ),
                ),
              ),

              // 控制按钮区域
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12.0),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: <Widget>[
                    // 开始/暂停按钮
                    ElevatedButton(
                      onPressed: () {
                        if (_state == 0 || _state == 2) {
                          _start();
                        } else {
                          _pause();
                        }
                      },
                      style: ElevatedButton.styleFrom(
                        shape: const CircleBorder(),
                        padding: const EdgeInsets.all(20),
                        backgroundColor:
                            _state == 1 ? Colors.orange : Colors.green,
                      ),
                      child: Icon(
                        _state == 1 ? Icons.pause : Icons.play_arrow,
                        color: Colors.white,
                        size: 30,
                      ),
                    ),

                    // 重置按钮
                    ElevatedButton(
                      onPressed: _elapsedTime > 0 ? _reset : null,
                      style: ElevatedButton.styleFrom(
                        shape: const CircleBorder(),
                        padding: const EdgeInsets.all(20),
                        backgroundColor: Colors.red,
                      ),
                      child: const Icon(
                        Icons.refresh,
                        color: Colors.white,
                        size: 30,
                      ),
                    ),
                  ],
                ),
              ),
              TextField(
                controller: pinController,
                decoration: InputDecoration(
                  border: OutlineInputBorder(),
                  labelText: 'PIN',
                ),
              ),
              SizedBox(height: 12),
              // 画面旋转模式选择
              Row(
                children: [
                  const Text('画面旋转：'),
                  const SizedBox(width: 8),
                  Expanded(
                    child: SegmentedButton<int>(
                      segments: const [
                        ButtonSegment(value: 0, label: Text('默认')),
                        ButtonSegment(value: 1, label: Text('左转90°')),
                        ButtonSegment(value: 2, label: Text('180°')),
                        ButtonSegment(value: 3, label: Text('右转90°')),
                      ],
                      selected: {_rotationMode},
                      onSelectionChanged: (Set<int> selected) {
                        setState(() {
                          _rotationMode = selected.first;
                        });
                        channel.invokeMethod('rotationMode', _rotationMode);
                      },
                    ),
                  ),
                ],
              ),
              SizedBox(height: 10),
              // 预览方向：切换查看「源是竖向」和「源是横向」两种情况下客户端看到的样子。
              // 这里只影响预览，实际填充方式由服务端按源画面方向自动决定。
              Row(
                children: [
                  const Text('预览方向：'),
                  const SizedBox(width: 8),
                  Expanded(
                    child: SegmentedButton<int>(
                      segments: const [
                        ButtonSegment(value: 0, label: Text('竖向')),
                        ButtonSegment(value: 1, label: Text('横向')),
                      ],
                      selected: {_previewOrientation},
                      onSelectionChanged: (Set<int> selected) {
                        setState(() {
                          _previewOrientation = selected.first;
                        });
                      },
                    ),
                  ),
                ],
              ),
              SizedBox(height: 12),
              // 效果预览：显示该源方向下客户端实际看到的画面
              RotationPreview(
                mode: _rotationMode,
                landscape: _previewOrientation == 1,
              ),
              SizedBox(height: 12),
              // 运行状态指示：颜色 + 文案 + 已连客户端数
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                decoration: BoxDecoration(
                  color: _serverRunning
                      ? const Color(0xFFE8F5E9)
                      : const Color(0xFFF5F5F5),
                  border: Border.all(
                    color: _serverRunning
                        ? const Color(0xFF66BB6A)
                        : const Color(0xFFBDBDBD),
                  ),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Row(
                  children: [
                    Container(
                      width: 12,
                      height: 12,
                      decoration: BoxDecoration(
                        color: _serverRunning
                            ? const Color(0xFF43A047)
                            : const Color(0xFF9E9E9E),
                        shape: BoxShape.circle,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(
                      _serverRunning ? '服务端运行中' : '服务端未运行',
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: _serverRunning
                            ? const Color(0xFF2E7D32)
                            : const Color(0xFF616161),
                      ),
                    ),
                    const SizedBox(width: 16),
                    Icon(
                      Icons.devices,
                      size: 16,
                      color: _clientCount > 0
                          ? const Color(0xFF2E7D32)
                          : const Color(0xFF9E9E9E),
                    ),
                    const SizedBox(width: 4),
                    Text(
                      '已连接客户端：$_clientCount',
                      style: TextStyle(
                        fontSize: 14,
                        color: _clientCount > 0
                            ? const Color(0xFF2E7D32)
                            : const Color(0xFF616161),
                      ),
                    ),
                  ],
                ),
              ),
              SizedBox(height: 12),
              Row(
                spacing: 16,
                children: [
                  TextButton(
                    onPressed: _serverRunning
                        ? null
                        : () {
                            channel.invokeMethod('start');
                          },
                    style: TextButton.styleFrom(
                      backgroundColor: Theme.of(context).colorScheme.primary,
                    ),
                    child: Row(
                      spacing: 4,
                      children: [
                        Icon(
                          Icons.play_arrow,
                          color: Theme.of(context).colorScheme.onPrimary,
                        ),
                        Text(
                          'Start Server',
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.onPrimary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  TextButton(
                    onPressed: _serverRunning
                        ? () {
                            channel.invokeMethod('stop');
                          }
                        : null,
                    style: TextButton.styleFrom(
                      backgroundColor: _serverRunning
                          ? const Color(0xFFD32F2F)
                          : null,
                    ),
                    child: Row(
                      spacing: 4,
                      children: [
                        Icon(
                          Icons.stop,
                          color: _serverRunning
                              ? const Color(0xFFFFFFFF)
                              : const Color(0xFF9E9E9E),
                        ),
                        Text(
                          'Stop Server',
                          style: TextStyle(
                            color: _serverRunning
                                ? const Color(0xFFFFFFFF)
                                : const Color(0xFF9E9E9E),
                          ),
                        ),
                      ],
                    ),
                  ),
                  TextButton(
                    onPressed: () {
                      String pin = pinController.text;
                      channel.invokeMethod('pin', pin);
                    },
                    style: TextButton.styleFrom(
                      backgroundColor: Theme.of(context).colorScheme.primary,
                    ),
                    child: Row(
                      spacing: 4,
                      children: [
                        Icon(
                          Icons.add,
                          color: Theme.of(context).colorScheme.onPrimary,
                        ),
                        Text(
                          'Add PIN',
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.onPrimary,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              SizedBox(height: 20),
              // ---------------- DLNA 投屏 ----------------
              // 和上面的 Moonlight 串流是两条独立链路：
              // 串流走 RTP 给 Moonlight 客户端；投屏把屏幕编成 TS 流，
              // 通过 UPnP 推给电视/投屏盒，接收端不需要装任何 App。
              Row(
                children: [
                  const Text('DLNA 投屏：',
                      style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      _dlnaRunning
                          ? '正在投屏到「$_dlnaTarget」'
                          : (_dlnaDevices.isEmpty
                              ? '未搜索设备'
                              : '找到 ${_dlnaDevices.length} 个设备'),
                      style: TextStyle(
                        fontSize: 13,
                        color: _dlnaRunning
                            ? const Color(0xFF2E7D32)
                            : const Color(0xFF616161),
                      ),
                    ),
                  ),
                ],
              ),
              // 音频轨状态：只在投屏中显示。没有音频时把原因讲清楚，
              // 免得用户以为是投屏坏了。最常见的原因是系统版本 < Android 10。
              if (_dlnaRunning && !_dlnaHasAudio && _dlnaAudioError.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Icon(Icons.volume_off,
                          size: 15, color: Color(0xFFE65100)),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          '无声音：$_dlnaAudioError',
                          style: const TextStyle(
                              fontSize: 12, color: Color(0xFFE65100)),
                        ),
                      ),
                    ],
                  ),
                ),
              // 运行统计：只在投屏中显示，用来排查延迟/卡顿/音画不同步。
              // 关键看两个数：码率是否接近上限、积压秒数是否在涨。
              if (_dlnaRunning && _dlnaStats != null)
                Container(
                  margin: const EdgeInsets.only(top: 8),
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: const Color(0xFFF5F5F5),
                    borderRadius: BorderRadius.circular(8),
                    border: Border.all(color: const Color(0xFFE0E0E0)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('运行状态',
                          style: TextStyle(
                              fontSize: 12, fontWeight: FontWeight.w600)),
                      const SizedBox(height: 6),
                      Wrap(
                        spacing: 16,
                        runSpacing: 4,
                        children: [
                          _statItem('码率', _dlnaStats!.bitrateText),
                          _statItem('出网', _dlnaStats!.servedText),
                          _statItem('连接', '${_dlnaStats!.clients}'),
                          _statItem('视频', '${_dlnaStats!.videoFrames} 帧'),
                          _statItem('音频', '${_dlnaStats!.audioFrames} 帧'),
                          _statItem(
                            '发送积压',
                            '${_dlnaStats!.backlogSeconds} 秒',
                            warn: _dlnaStats!.backlogSeconds > 3,
                          ),
                        ],
                      ),
                      const Padding(
                        padding: EdgeInsets.only(top: 6),
                        child: Text('发送积压不包含电视或播放器内部缓冲',
                            style: TextStyle(fontSize: 11, color: Colors.black54)),
                      ),
                      if (!_dlnaStats!.clientActive)
                        const Padding(
                          padding: EdgeInsets.only(top: 6),
                          child: Text(
                            '接收端已停止拉流（可能已断开或卡住）',
                            style: TextStyle(
                                fontSize: 11, color: Color(0xFFD32F2F)),
                          ),
                        )
                      else if (_dlnaStats!.backlogSeconds > 3)
                        const Padding(
                          padding: EdgeInsets.only(top: 6),
                          child: Text(
                            '积压持续增长说明接收端吞吐不足，画面会越来越滞后',
                            style: TextStyle(
                                fontSize: 11, color: Color(0xFFE65100)),
                          ),
                        ),
                    ],
                  ),
                ),
              SizedBox(height: 8),
              if (_dlnaDevices.isNotEmpty && !_dlnaRunning)
                Container(
                  decoration: BoxDecoration(
                    border: Border.all(color: const Color(0xFFBDBDBD)),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Column(
                    children: [
                      for (int i = 0; i < _dlnaDevices.length; i++)
                        InkWell(
                          onTap: () {
                            channel.invokeMethod('dlnaStart', i);
                          },
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                                horizontal: 12, vertical: 10),
                            decoration: BoxDecoration(
                              border: i < _dlnaDevices.length - 1
                                  ? const Border(
                                      bottom: BorderSide(
                                          color: Color(0xFFE0E0E0)))
                                  : null,
                            ),
                            child: Row(
                              children: [
                                const Icon(Icons.tv, size: 18),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: Text(
                                    _dlnaDevices[i]['name'] as String? ?? '未知设备',
                                    style: const TextStyle(fontSize: 14),
                                  ),
                                ),
                                const Icon(Icons.play_arrow,
                                    size: 18, color: Color(0xFF2E7D32)),
                              ],
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              SizedBox(height: 8),
              Row(
                spacing: 12,
                children: [
                  TextButton.icon(
                    onPressed: _dlnaScanning
                        ? null
                        : () {
                            setState(() {
                              _dlnaScanning = true;
                              _dlnaDevices = [];
                            });
                            channel.invokeMethod('dlnaDiscover').then((value) {
                              if (!mounted) return;
                              final list = <Map<String, dynamic>>[];
                              if (value is List) {
                                for (final item in value) {
                                  if (item is Map) {
                                    list.add(Map<String, dynamic>.from(item));
                                  }
                                }
                              }
                              setState(() {
                                _dlnaDevices = list;
                                _dlnaScanning = false;
                              });
                            }).catchError((_) {
                              if (mounted) {
                                setState(() => _dlnaScanning = false);
                              }
                            });
                          },
                    style: TextButton.styleFrom(
                      backgroundColor: Theme.of(context).colorScheme.primary,
                    ),
                    icon: Icon(
                      _dlnaScanning ? Icons.hourglass_empty : Icons.search,
                      size: 18,
                      color: Theme.of(context).colorScheme.onPrimary,
                    ),
                    label: Text(
                      _dlnaScanning ? '搜索中…' : '搜索设备',
                      style: TextStyle(
                        color: Theme.of(context).colorScheme.onPrimary,
                      ),
                    ),
                  ),
                  if (_dlnaRunning)
                    TextButton.icon(
                      onPressed: () {
                        channel.invokeMethod('dlnaStop');
                        setState(() {
                          _dlnaRunning = false;
                          _dlnaTarget = '';
                        });
                      },
                      style: TextButton.styleFrom(
                        backgroundColor: const Color(0xFFD32F2F),
                      ),
                      icon: const Icon(Icons.stop, size: 18, color: Colors.white),
                      label: const Text('停止投屏',
                          style: TextStyle(color: Colors.white)),
                    ),
                ],
              ),
              SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }
}
