# 第三方说明

慧生活798接口序列化、登录签名和请求参数参考 https://github.com/tunecc/drink_798 （2026-11-09 main）。部分代码按 MIT 许可改写，许可证见 LICENSE-drink_798.txt。

二维码识别使用 ZXing Core 3.5.3，Apache License 2.0，Copyright ZXing authors；项目 https://github.com/zxing/zxing 。许可证见 LICENSE-zxing.txt。源码发行包内的 jar 为 Maven Central 官方制品。

离线测试使用 org.json 20240303（公共领域），不打包进应用，Android 自带 org.json。

支付宝登录、签约和支付使用 `com.alipay.sdk:alipaysdk-android:15.8.35`，来自 Maven Central 官方发布制品。源码包内的 `libs/alipaysdk-15.8.35.jar` 和 `res/values*/values*.xml` 从其 AAR 提取，SDK 权利及使用条款归支付宝所有，不受本项目引用的 MIT/Apache 许可覆盖。官方接入文档：https://opendocs.alipay.com/open/204/105296 。快捷登录使用用户授权分析的官方APK公开内置商户签名请求，来源记录见 ALIPAY_LOGIN.md。

设备支付渠道、签约协议类型、账单结构和支付确认流程另外对照了 https://github.com/wu-shanglin/Smartlife798 的公开协议分析文档（2026-11-09读取）。本项目独立编写 Java 实现，没有移入该项目的业务源码。文档中未验证的推测未用作授权成功依据。

住理生活接口根据用户授权从本人手机的 3.12.11 安装包和官方页面中分析；支付宝登录通过平台动态返回的授权参数和官方SDK完成。应用 UI、图标与业务代码为本次新编写。不随发行包提供原版 APK、官方网页源码、广告素材或任何真实账户信息。

本应用与上述服务的运营方无关，账单、费率、权限、余额和订单最终状态均由各平台处理。
