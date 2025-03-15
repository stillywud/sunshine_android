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
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    channel.invokeMethod('address').then((value) {
      setState(() {
        address = value;
      });
    });
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
        child: Column(
          children: <Widget>[
            // 秒表显示区域
            Expanded(
              flex: 2,
              child: Center(
                child: Text(
                  _formatTime(_elapsedTime),
                  style: const TextStyle(
                    fontSize: 60,
                    fontWeight: FontWeight.bold,
                    fontFamily: 'monospace',
                  ),
                ),
              ),
            ),

            // 控制按钮区域
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 20.0),
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
                      backgroundColor: _state == 1 ? Colors.orange : Colors.green,
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
            Row(
              spacing: 16,
              children: [
                TextButton(
                  onPressed: () {
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
            SizedBox(
              height: 100,
            ),
          ],
        ),
      ),
    );
  }
}
