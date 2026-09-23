//! The PC's TLS identity and the phones it has paired with.
//!
//! The certificate is created once on first run and kept in
//! `%APPDATA%\PhoneCam`. The phone remembers it after the user approves the
//! pairing, which is what lets later connections start without any codes.

use std::path::PathBuf;
use std::sync::Arc;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};
use serde_json::{json, Value};

pub struct Identity {
    pub cert: CertificateDer<'static>,
    key: Vec<u8>,
    pub fingerprint: String,
}

#[derive(Clone, Debug)]
pub struct Phone {
    pub fingerprint: String,
    pub name: String,
    pub address: String,
}

pub fn data_dir() -> PathBuf {
    let base = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    base.join("PhoneCam")
}

pub fn fingerprint(der: &[u8]) -> String {
    ring::digest::digest(&ring::digest::SHA256, der).as_ref().iter().map(|b| format!("{b:02x}")).collect()
}

/// Six digits shown on both screens during pairing; mirrors `Identity.verificationCode` on the phone.
pub fn verification_code(a: &str, b: &str) -> String {
    let (x, y) = if a <= b { (a, b) } else { (b, a) };
    let d = ring::digest::digest(&ring::digest::SHA256, format!("{x}:{y}").as_bytes());
    let d = d.as_ref();
    let n = ((d[0] as u32) << 16) | ((d[1] as u32) << 8) | d[2] as u32;
    format!("{:06}", n % 1_000_000)
}

pub fn computer_name() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "PC".into())
}

impl Identity {
    pub fn load_or_create() -> Result<Self, String> {
        let dir = data_dir();
        let (cert_path, key_path) = (dir.join("identity.crt"), dir.join("identity.key"));
        if let (Ok(cert), Ok(key)) = (std::fs::read(&cert_path), std::fs::read(&key_path)) {
            let fingerprint = fingerprint(&cert);
            return Ok(Self { cert: CertificateDer::from(cert), key, fingerprint });
        }
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let key = rcgen::KeyPair::generate_for(&rcgen::PKCS_ECDSA_P256_SHA256).map_err(|e| e.to_string())?;
        let mut params = rcgen::CertificateParams::new(vec!["phonecam-pc".into()]).map_err(|e| e.to_string())?;
        params.distinguished_name.push(rcgen::DnType::CommonName, format!("PhoneCam {}", computer_name()));
        params.not_before = rcgen::date_time_ymd(2024, 1, 1);
        params.not_after = rcgen::date_time_ymd(2100, 1, 1);
        let cert = params.self_signed(&key).map_err(|e| e.to_string())?;
        let (cert, key) = (cert.der().to_vec(), key.serialize_der());
        std::fs::write(&cert_path, &cert).map_err(|e| e.to_string())?;
        std::fs::write(&key_path, &key).map_err(|e| e.to_string())?;
        let fingerprint = fingerprint(&cert);
        Ok(Self { cert: CertificateDer::from(cert), key, fingerprint })
    }

    pub fn client_config(&self) -> Result<Arc<ClientConfig>, String> {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let config = ClientConfig::builder_with_provider(provider.clone())
            .with_protocol_versions(&[&rustls::version::TLS13])
            .map_err(|e| e.to_string())?
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(PinnedLater { provider }))
            .with_client_auth_cert(vec![self.cert.clone()], PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key.clone())))
            .map_err(|e| e.to_string())?;
        Ok(Arc::new(config))
    }
}

/// Paired phones, most recent first.
pub fn phones() -> Vec<Phone> {
    let raw = std::fs::read_to_string(data_dir().join("phones.json")).unwrap_or_default();
    let list: Vec<Value> = serde_json::from_str(&raw).unwrap_or_default();
    list.iter()
        .filter_map(|v| {
            Some(Phone {
                fingerprint: v.get("fingerprint")?.as_str()?.to_string(),
                name: v.get("name").and_then(Value::as_str).unwrap_or("Phone").to_string(),
                address: v.get("address").and_then(Value::as_str).unwrap_or_default().to_string(),
            })
        })
        .collect()
}

pub fn remember(phone: &Phone) {
    let mut list = phones();
    list.retain(|p| p.fingerprint != phone.fingerprint);
    list.insert(0, phone.clone());
    let json: Vec<Value> = list
        .iter()
        .map(|p| json!({ "fingerprint": p.fingerprint, "name": p.name, "address": p.address }))
        .collect();
    let _ = std::fs::create_dir_all(data_dir());
    let _ = std::fs::write(data_dir().join("phones.json"), serde_json::to_string_pretty(&json).unwrap());
}

pub fn forget(fingerprint: &str) {
    let mut list = phones();
    list.retain(|p| p.fingerprint != fingerprint);
    let json: Vec<Value> = list.iter().map(|p| json!({ "fingerprint": p.fingerprint, "name": p.name, "address": p.address })).collect();
    let _ = std::fs::write(data_dir().join("phones.json"), serde_json::to_string_pretty(&json).unwrap());
}

/// Accepts any phone certificate during the handshake but still checks that
/// the phone owns its key. Whether that certificate is *trusted* is decided
/// right after, by fingerprint, since an unpaired phone has to be able to
/// complete the handshake to be paired at all.
#[derive(Debug)]
struct PinnedLater {
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinnedLater {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn verification_code_is_symmetric() {
        assert_eq!(verification_code("aa", "bb"), verification_code("bb", "aa"));
        assert_eq!(verification_code("aa", "bb").len(), 6);
    }

    #[test]
    fn verification_code_matches_phone() {
        // Same formula as the Kotlin implementation: SHA-256 of "min:max", first three bytes.
        let d = ring::digest::digest(&ring::digest::SHA256, b"a:b");
        let n = ((d.as_ref()[0] as u32) << 16) | ((d.as_ref()[1] as u32) << 8) | d.as_ref()[2] as u32;
        assert_eq!(verification_code("b", "a"), format!("{:06}", n % 1_000_000));
    }
}
