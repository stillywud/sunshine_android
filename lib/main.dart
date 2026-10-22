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

/// 画面旋转效果预览。
///
/// 用 16:9 的编码帧模拟客户端屏幕，把一块 9:16 的"手机屏幕"按当前旋转模式
/// 摆进去，让用户在连接之前就能直观看到实际传输效果：
///   - 默认：不旋转，竖屏画面居中，两侧黑边
///   - 左转90°/右转90°：画面转 90°，正好铺满 16:9
///   - 180°：上下颠倒，仍保持竖屏比例居中
///
/// [fillMode] 为 true 时改为裁剪铺满：画面放大到盖住整个帧，超出部分被裁掉，
/// 没有黑边，比例也不变形。
class RotationPreview extends StatelessWidget {
  const RotationPreview({super.key, required this.mode, this.fillMode = 0});

  /// 0=默认，1=左转90°，2=180°，3=右转90°
  final int mode;

  /// 0=适应（留黑边），1=裁剪铺满（无黑边）
  final int fillMode;

  @override
  Widget build(BuildContext context) {
    // 源画面是竖屏手机屏幕（模拟 9:16），客户端帧是 16:9
    const double sourceAspect = 9 / 16;
    const double frameAspect = 16 / 9;

    // 旋转 90/270 后源画面的可见宽高互换
    final bool swapped = mode == 1 || mode == 3;
    final double visibleAspect = swapped ? 1 / sourceAspect : sourceAspect;

    final bool fill = fillMode != 0;
    final bool sourceIsWider = visibleAspect > frameAspect;

    // 与 gl_renderer 的 compute_fit 保持一致：
    //   fit  : 溢出的轴缩小，留下黑边
    //   fill : 不足的轴放大，超出部分被裁掉
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

  /// 模拟一块竖屏手机屏幕，用可辨识的图形让旋转方向一目了然
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
  // 填充方式：0=适应（留黑边），1=裁剪铺满（无黑边）
  int _fillMode = 0;
  // 服务端是否在运行（由 native 主循环状态决定）
  bool _serverRunning = false;
  // 当前连上来的客户端数量
  int _clientCount = 0;
  // 状态轮询定时器
  Timer? _statusTimer;

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
    channel.invokeMethod('getFillMode').then((value) {
      if (value is int && mounted) {
        setState(() {
          _fillMode = value;
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
              // 填充方式：比例不匹配时是留黑边还是裁掉边缘铺满
              Row(
                children: [
                  const Text('填充方式：'),
                  const SizedBox(width: 8),
                  Expanded(
                    child: SegmentedButton<int>(
                      segments: const [
                        ButtonSegment(value: 0, label: Text('适应')),
                        ButtonSegment(value: 1, label: Text('裁剪铺满')),
                      ],
                      selected: {_fillMode},
                      onSelectionChanged: (Set<int> selected) {
                        setState(() {
                          _fillMode = selected.first;
                        });
                        channel.invokeMethod('fillMode', _fillMode);
                      },
                    ),
                  ),
                ],
              ),
              SizedBox(height: 12),
              // 旋转效果预览：选完模式立刻能看到实际传输效果
              RotationPreview(mode: _rotationMode, fillMode: _fillMode),
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
              SizedBox(height: 24),
            ],
          ),
        ),
      ),
    );
  }
}
