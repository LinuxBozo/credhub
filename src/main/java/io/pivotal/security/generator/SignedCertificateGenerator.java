package io.pivotal.security.generator;

import io.pivotal.security.credential.CertificateCredentialValue;
import io.pivotal.security.domain.CertificateParameters;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.security.auth.x500.X500Principal;

import static io.pivotal.security.util.StringUtil.UTF_8;
import static org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils.parseExtensionValue;

@Component
public class SignedCertificateGenerator {

  private final DateTimeProvider timeProvider;
  private final RandomSerialNumberGenerator serialNumberGenerator;
  private final JcaX509ExtensionUtils jcaX509ExtensionUtils;
  private JcaContentSignerBuilder jcaContentSignerBuilder;
  private JcaX509CertificateConverter jcaX509CertificateConverter;
  private final BouncyCastleProvider jceProvider;

  @Autowired
  SignedCertificateGenerator(
      DateTimeProvider timeProvider,
      RandomSerialNumberGenerator serialNumberGenerator,
      JcaContentSignerBuilder jcaContentSignerBuilder,
      JcaX509CertificateConverter jcaX509CertificateConverter,
      BouncyCastleProvider jceProvider
  ) throws Exception {
    this.timeProvider = timeProvider;
    this.serialNumberGenerator = serialNumberGenerator;
    this.jcaX509ExtensionUtils = new JcaX509ExtensionUtils();
    this.jcaContentSignerBuilder = jcaContentSignerBuilder;
    this.jcaX509CertificateConverter = jcaX509CertificateConverter;
    this.jceProvider = jceProvider;
  }

  X509Certificate getSelfSigned(KeyPair keyPair, CertificateParameters params) throws Exception {
    SubjectKeyIdentifier keyIdentifier = getSubjectKeyIdentifierFromKeyInfo(keyPair.getPublic());

    return getSignedByIssuer(
        null,
        keyPair.getPrivate(),
        params.getX500Principal(),
        keyIdentifier,
        keyPair,
        params
    );
  }

  X509Certificate getSignedByIssuer(
      KeyPair keyPair,
      CertificateParameters params,
      CertificateCredentialValue ca
  ) throws Exception {
    X509Certificate caCertificate = getX509Certificate(ca);
    return getSignedByIssuer(
        caCertificate, getPrivateKeyFrom(ca.getPrivateKey()), getSubjectNameFrom(caCertificate),
        getSubjectKeyIdentifierFrom(caCertificate), keyPair,
        params
    );
  }

  private X509Certificate getSignedByIssuer(
      X509Certificate issuerCertificate,
      PrivateKey issuerKey,
      X500Principal issuerDn,
      SubjectKeyIdentifier caSubjectKeyIdentifier,
      KeyPair keyPair,
      CertificateParameters params) throws Exception {
    Instant now = timeProvider.getNow().toInstant();

    BigInteger certificateSerialNumber = serialNumberGenerator.generate();
    BigInteger caSerialNumber =
        issuerCertificate != null ? issuerCertificate.getSerialNumber() : certificateSerialNumber;

    final JcaX509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
        issuerDn,
        certificateSerialNumber,
        Date.from(now),
        Date.from(now.plus(Duration.ofDays(params.getDuration()))),
        params.getX500Principal(),
        keyPair.getPublic()
    );

    certificateBuilder.addExtension(
        Extension.subjectKeyIdentifier,
        false,
        getSubjectKeyIdentifierFromKeyInfo(keyPair.getPublic()));
    if (params.getAlternativeNames() != null) {
      certificateBuilder
          .addExtension(Extension.subjectAlternativeName, false, params.getAlternativeNames());
    }

    if (params.getKeyUsage() != null) {
      certificateBuilder.addExtension(Extension.keyUsage, true, params.getKeyUsage());
    }

    if (params.getExtendedKeyUsage() != null) {
      certificateBuilder
          .addExtension(Extension.extendedKeyUsage, false, params.getExtendedKeyUsage());
    }

    if (caSubjectKeyIdentifier.getKeyIdentifier() != null) {
      PublicKey issuerPublicKey = issuerCertificate != null ? issuerCertificate.getPublicKey() : keyPair.getPublic();
      AuthorityKeyIdentifier authorityKeyIdentifier = jcaX509ExtensionUtils
          .createAuthorityKeyIdentifier(issuerPublicKey, issuerDn, caSerialNumber);

      certificateBuilder
          .addExtension(Extension.authorityKeyIdentifier, false, authorityKeyIdentifier);
    }

    certificateBuilder
        .addExtension(Extension.basicConstraints, true, new BasicConstraints(params.isCa()));

    ContentSigner contentSigner = jcaContentSignerBuilder.build(issuerKey);

    X509CertificateHolder holder = certificateBuilder.build(contentSigner);

    return jcaX509CertificateConverter.getCertificate(holder);
  }

  private SubjectKeyIdentifier getSubjectKeyIdentifierFromKeyInfo(PublicKey publicKey) {
    return jcaX509ExtensionUtils.createSubjectKeyIdentifier(publicKey);
  }

  private X500Principal getSubjectNameFrom(X509Certificate certificate) throws IOException, CertificateException {
    return new X500Principal(certificate.getSubjectX500Principal().getEncoded());
  }

  private PrivateKey getPrivateKeyFrom(String privateKeyPem)
      throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
    PEMParser pemParser = new PEMParser(new StringReader(privateKeyPem));
    PEMKeyPair pemKeyPair = (PEMKeyPair) pemParser.readObject();
    PrivateKeyInfo privateKeyInfo = pemKeyPair.getPrivateKeyInfo();
    return new JcaPEMKeyConverter().getPrivateKey(privateKeyInfo);
  }

  private SubjectKeyIdentifier getSubjectKeyIdentifierFrom(X509Certificate certificate) throws Exception {
    byte[] extensionValue = certificate.getExtensionValue(Extension.subjectKeyIdentifier.getId());
    return extensionValue == null ?
        new SubjectKeyIdentifier(null) :
        SubjectKeyIdentifier.getInstance(parseExtensionValue(extensionValue));
  }

  private X509Certificate getX509Certificate(CertificateCredentialValue ca) throws CertificateException {
    return (X509Certificate) CertificateFactory
        .getInstance("X.509", jceProvider)
        .generateCertificate(new ByteArrayInputStream(ca.getCertificate().getBytes(UTF_8)));
  }
}
