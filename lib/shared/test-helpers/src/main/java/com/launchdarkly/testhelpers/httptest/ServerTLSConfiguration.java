package com.launchdarkly.testhelpers.httptest;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

/**
 * Holds all certificate/key data required to configure an {@link HttpServer} for HTTPS,
 * and to configure a client to make requests to that server.
 * <p>
 * This implementation uses OkHttp's {@code okhttp-tls} package.
 */
public final class ServerTLSConfiguration {
  private final X509Certificate certificate;
  private final PrivateKey privateKey;
  private final PublicKey publicKey;
  private final SSLSocketFactory socketFactory;
  private final X509TrustManager trustManager;
  
  private ServerTLSConfiguration(X509Certificate certificate, PrivateKey privateKey, PublicKey publicKey,
      SSLSocketFactory socketFactory, X509TrustManager trustManager) {
    this.certificate = certificate;
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.socketFactory = socketFactory;
    this.trustManager = trustManager;
  }

  /**
   * Creates an instance with a self-signed certificate.
   * <p>
   * HTTP clients will normally reject this certificate. To configure a client to accept it,
   * use the objects provided by {@link #getSocketFactory()} and {@link #getTrustManager()}.
   * <p>
   * The certificate's hostname is "localhost". It expires in 24 hours.
   * 
   * @return a {@link ServerTLSConfiguration}
   */
  public static ServerTLSConfiguration makeSelfSignedCertificate() {
    String hostname;
    try {
      hostname = InetAddress.getByName("localhost").getCanonicalHostName();
    } catch (UnknownHostException e) {
      throw new RuntimeException(e);
    }
    
    HeldCertificate certInfo = new HeldCertificate.Builder()
      .rsa2048()
      .serialNumber(BigInteger.ONE)
      .certificateAuthority(1)
      .commonName(hostname)
      .addSubjectAlternativeName("localhost")
      .build();

    HandshakeCertificates hc = new HandshakeCertificates.Builder()
        .heldCertificate(certInfo)
        .addTrustedCertificate(certInfo.certificate())
        .build();

    return new ServerTLSConfiguration(
        certInfo.certificate(),
        certInfo.keyPair().getPrivate(),
        certInfo.keyPair().getPublic(),
        hc.sslSocketFactory(),
        hc.trustManager()
        );
  }

  /**
   * Returns the server certificate.
   * 
   * @return the server certificate
   */
  public X509Certificate getCertificate() {
    return certificate;
  }

  /**
   * Returns the private key.
   * 
   * @return the private key
   */
  public PrivateKey getPrivateKey() {
    return privateKey;
  }

  /**
   * Returns the public key.
   * 
   * @return the public key
   */
  public PublicKey getPublicKey() {
    return publicKey;
  }

  /**
   * Returns an {@link SSLSocketFactory} for use by the client.
   * 
   * @return an {@link SSLSocketFactory}
   */
  public SSLSocketFactory getSocketFactory() {
    return socketFactory;
  }

  /**
   * Returns a {@link TrustManager} for use by the client.
   * 
   * @return a {@link TrustManager} 
   */
  public X509TrustManager getTrustManager() {
    return trustManager;
  }
}
