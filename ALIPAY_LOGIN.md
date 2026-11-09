# 支付宝快捷登录接入记录

## 来源

用户授权分析的慧生活798 Android 3.1.9安装包内，内层DEX头部及前4096字节经过处理，业务字符串仍存在。通过保留的 `map_list` 定位内层DEX并重建字符串索引，得到用于本地静态分析的副本。没有修改或重新安装官方APK，也没有访问支付宝或官方应用的私有账户存储。

找到 `LoginActivity$handleAliAuthLoginClick$1` 的 `authInfo` 捕获字段、`com.ilife.lib.pay.data.ali.AuthResult` 解析逻辑，以及 `LoginParm` 的 `un/authCode/openCode` 字段。部分登录方法体仍不可恢复，`auth_code → openCode` 的实际可用性由本次真实SDK登录与平台账户校验确认。

APK内置的签名请求属性：

- `app_id=2021002141696106`
- `apiname=com.alipay.account.auth`
- `methodname=alipay.open.auth.sdk.code.get`
- `product_id=APP_FAST_LOGIN`
- `scope=kuaijie`
- `sign_type=RSA2`

原样请求长625字节，SHA-256为 `c1318585f3edb6b7692adf93f00472bbc1c32dda002d9d2b63a0bed636b4ce3f`，保存在 `res/raw/alipay_login_request.txt`。这是官方客户端公开内置的商户签名请求，不含本用户的手机号、授权码、会话或商户私钥。它并非我方重新签名生成的请求。

## 实现

1. 原生层调用支付宝SDK `AuthTask.authV2`；页面只请求发起登录，不接收完整SDK回调。
2. 只有SDK状态9000、结果码200且含唯一非空授权码时才继续。取消、失败、歧义或缺失值均终止。
3. 将授权码放在JSON的 `openCode`，按现有798登录参数签名规则POST至 `/api/v1/acc/login`。无自动重试；授权码不记录日志、不持久化。
4. 用候选会话读取账户，核对UID后才写入本机Keystore加密存储。存在待确认订单或支付时，拒绝切换到另一账户。
5. SDK登录与免密签约/付款入口独立，登录流程不调用 `sign-pga`、`prepay` 或设备启停接口。

## 已验证与限制

2026-11-09在用户手机上完成登录，读取实际账户成功；应用重启后会话和账户刷新仍正常。179条离线断言及浏览器界面回归通过。没有执行真实付款或设备启停。

这属于第三方客户端的兼容实现，不代表平台授予了官方客户端身份或稳定接口保证。若平台更换签名请求、撤销兼容性或要求补充账户绑定，应重新核实或回到官方流程，不能伪造签名、授权码或成功回调。
