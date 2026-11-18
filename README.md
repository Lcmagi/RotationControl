# RotationControl 自用 Fork

原项目 RotationControl 由 **benbaobaoshigemi** 开发。本仓库是 **Lcmagi** 维护的个人修改版。软件介绍、安装与使用说明请查看[原作者项目页面](https://github.com/benbaobaoshigemi/RotationControl)。

## 本 Fork 的修改

| 分支 | 修改与验证 |
| --- | --- |
| [手势去重版](https://github.com/Lcmagi/RotationControl/tree/fix/dual-channel-touch-stream) | 同一次触摸由最先收到按下事件的通道处理，修复三指三击反复停在第一击的问题。已在小米 18 Fold 实机确认三指三击可以切换旋转锁定。 |
| [触摸性能版](https://github.com/Lcmagi/RotationControl/tree/fix/touch-path-performance) | 普通单指移动提前返回，多指移动复用显示信息，减少触摸期间的日志。保留两条输入通道和三种手势。 |
| [折叠恢复版（当前维护）](https://github.com/Lcmagi/RotationControl/tree/fix/fold-recovery) | 合盖或展开后，若旋转设置仍与模块写入的值一致，恢复手势前的设置。切屏时丢弃旧面板上排队的动作，设置部分写入失败后可恢复。锁屏时屏蔽旋转手势的执行。 |

折叠恢复版的 26 项 JVM 回归测试已通过。切屏判断依赖系统报告不同的面板物理尺寸，小米 18 Fold 的折叠恢复效果仍待实机验证。暂无整机功耗或延迟实测数据。

## 构建与许可

在[折叠恢复版构建记录](https://github.com/Lcmagi/RotationControl/actions/workflows/build-fixed-apk.yml?query=branch%3Afix%2Ffold-recovery)中选择成功运行，下载产物 `RotationControl-fold-recovery`，解压即可取得 APK。

Copyright 2026 benbaobaoshigemi。原项目及本 Fork 的修改沿用 Apache License 2.0。再分发时请保留适用的原作者声明、附上许可证全文，并在修改过的文件中注明变更。[原项目版权与许可声明](LICENSE) · [Apache License 2.0 全文](https://www.apache.org/licenses/LICENSE-2.0)
