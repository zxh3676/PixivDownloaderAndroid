//! pxdl_ech —— Pixiv 下载器安卓版的 ECH 直连 JNI 库
//! ============================================================
//! 原理（与 PixEz/rhttp 相同, 见 pxdl-py/ech_helper 与 Windows 版助手）:
//!   - TLS 握手使用 rustls 的 ECH 模式: 外层 ClientHello 的 SNI 是
//!     cloudflare-ech.com (ECHConfig 内含), 真实域名加密于内层,
//!     GFW 无法读取, 因此不会重置连接。
//!   - 连接目标为 Cloudflare 边缘 anycast IP (由 Kotlin 侧传入)。
//!   - 不校验服务器证书 (与 App 其余部分行为一致)。
//!
//! Kotlin 侧通过 EchClient.nativeRequest 调用, 传入:
//!   url          完整 https URL
//!   ech_config   ECHConfig base64 (Kotlin 侧从 DoH 获取并缓存)
//!   resolve_ip   直连的 CF IP
//!   headers_json JSON 对象字符串 {"K":"V", ...}
//!   timeout_ms   整体超时
//! 返回 JSON 字符串: {"status":200,"body_b64":"..."} 或 {"status":0,"error":"..."}

use jni::objects::{JClass, JString};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::sync::{Arc, OnceLock};

use base64::Engine as _;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::client::{EchConfig, EchMode};
use rustls::crypto::{aws_lc_rs, CryptoProvider};
use rustls::pki_types::{CertificateDer, EchConfigListBytes, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};

#[derive(Debug)]
struct NoVerifier;

impl ServerCertVerifier for NoVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _certificate: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _certificate: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::RSA_PKCS1_SHA256,
            SignatureScheme::RSA_PKCS1_SHA384,
            SignatureScheme::RSA_PKCS1_SHA512,
            SignatureScheme::RSA_PSS_SHA256,
            SignatureScheme::RSA_PSS_SHA384,
            SignatureScheme::RSA_PSS_SHA512,
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::ED25519,
        ]
    }
}

fn build_ech_tls_config(ech_config_list: &[u8]) -> Result<ClientConfig, String> {
    let provider: Arc<CryptoProvider> = CryptoProvider::get_default()
        .cloned()
        .unwrap_or_else(|| Arc::new(aws_lc_rs::default_provider()));

    let ech_config = EchConfig::new(
        EchConfigListBytes::from(ech_config_list.to_vec()),
        aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
    )
    .map_err(|e| format!("invalid ECH config: {e:?}"))?;

    let builder = ClientConfig::builder_with_provider(provider)
        .with_ech(EchMode::from(ech_config))
        .map_err(|e| format!("ECH setup failed: {e:?}"))?;

    Ok(builder
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoVerifier))
        .with_no_client_auth())
}

fn runtime() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

async fn do_request(
    url: String,
    ech_b64: String,
    ip: String,
    headers: Vec<(String, String)>,
    timeout_ms: i32,
) -> Result<Value, String> {
    let parsed = reqwest::Url::parse(&url).map_err(|e| format!("bad url: {e:?}"))?;
    let host = parsed
        .host_str()
        .ok_or("url has no host")?
        .to_string();
    let port = parsed.port_or_known_default().unwrap_or(443);

    let ech_bytes = base64::engine::general_purpose::STANDARD
        .decode(ech_b64.trim())
        .map_err(|e| format!("ECH base64 解码失败: {e:?}"))?;
    let tls = build_ech_tls_config(&ech_bytes)?;

    let mut builder = reqwest::Client::builder()
        .tls_backend_preconfigured(tls)
        .timeout(std::time::Duration::from_millis(timeout_ms.max(1000) as u64))
        .connect_timeout(std::time::Duration::from_secs(15))
        .redirect(reqwest::redirect::Policy::limited(5));

    let addr: std::net::SocketAddr = format!("{ip}:{port}")
        .parse()
        .map_err(|e| format!("bad ip: {e:?}"))?;
    builder = builder.resolve_to_addrs(&host, &[addr]);

    let client = builder.build().map_err(|e| format!("client build failed: {e:?}"))?;

    let mut req = client.get(parsed);
    for (k, v) in &headers {
        req = req.header(k, v);
    }
    let resp = req.send().await.map_err(|e| format!("request failed: {e}"))?;
    let status = resp.status().as_u16();
    let body = resp
        .bytes()
        .await
        .map_err(|e| format!("body read failed: {e}"))?;

    Ok(json!({
        "status": status,
        "body_b64": base64::engine::general_purpose::STANDARD.encode(&body),
    }))
}

/// Kotlin 入口: EchClient.nativeRequest(url, echConfig, resolveIp, headersJson, timeoutMs)
#[no_mangle]
pub extern "system" fn Java_com_pixiv_downloader_network_EchClient_nativeRequest<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    ech_config: JString<'local>,
    resolve_ip: JString<'local>,
    headers_json: JString<'local>,
    timeout_ms: i32,
) -> JString<'local> {
    let inner = (|| -> Result<Value, String> {
        let url: String = env.get_string(&url).map_err(|e| e.to_string())?.into();
        let cfg: String = env.get_string(&ech_config).map_err(|e| e.to_string())?.into();
        let ip: String = env.get_string(&resolve_ip).map_err(|e| e.to_string())?.into();
        let hdr_json: String = env.get_string(&headers_json).map_err(|e| e.to_string())?.into();
        let mut headers = Vec::new();
        if let Ok(Value::Object(map)) = serde_json::from_str::<Value>(&hdr_json) {
            for (k, v) in map {
                if let Some(s) = v.as_str() {
                    headers.push((k, s.to_string()));
                }
            }
        }
        runtime().block_on(do_request(url, cfg, ip, headers, timeout_ms))
    })();

    let text = match inner {
        Ok(v) => v.to_string(),
        Err(e) => json!({ "status": 0, "error": e }).to_string(),
    };
    env.new_string(text).expect("new_string failed")
}
