# RotationControl 自用 Fork

本仓库由 Lcmagi 从 benbaobaoshigemi 的 RotationControl 分叉，供个人使用。

软件介绍、安装与使用说明请查看 [原作者的项目页面](https://github.com/benbaobaoshigemi/RotationControl)。

本仓库沿用 [Apache License 2.0](LICENSE)。

## 修复内容

- **现象**：三指三击无法切换旋转锁定，敲击计数反复停在第 1 击。
- **原因**：输入管理服务（IMS）和窗口管理服务（WMS）会把同一段触摸重复交给手势检测器。旧逻辑只对移动事件去重，重复的按下或抬起事件会打乱敲击计数。
- **修复**：从第一指按下到最后一指抬起或手势取消，每段触摸只由最先收到按下事件的通道处理，并丢弃另一通道迟到的重复事件。修复代码位于 [`fix/dual-channel-touch-stream` 分支](https://github.com/Lcmagi/RotationControl/tree/fix/dual-channel-touch-stream)。
- **验证**：7 项触摸路由回归测试通过。小米 18 Fold 实测确认三指三击恢复切换旋转锁定。
