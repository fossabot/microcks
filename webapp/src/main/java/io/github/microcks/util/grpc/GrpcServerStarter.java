/*
 * Copyright The Microcks Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.microcks.util.grpc;

import io.grpc.Grpc;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.TlsServerCredentials;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This is starter component for building, starting and managing shutdown of a GRPC server handling mock calls.
 * @author laurent
 */
@Component
public class GrpcServerStarter {

   /** A simple logger for diagnostic messages. */
   private static Logger log = LoggerFactory.getLogger(GrpcServerStarter.class);

   private static final String BEGIN_RSA_PRIVATE_KEY = "-----BEGIN RSA PRIVATE KEY-----";

   private static final String END_RSA_PRIVATE_KEY = "-----END RSA PRIVATE KEY-----";

   @Value("${grpc.server.port:9090}")
   private final Integer serverPort = 9090;

   @Value("${grpc.server.certChainFilePath:}")
   private final String certChainFilePath = null;

   @Value("${grpc.server.privateKeyFilePath:}")
   private final String privateKeyFilePath = null;

   @Autowired
   private GrpcMockHandlerRegistry mockHandlerRegistry;

   private AtomicBoolean isRunning = new AtomicBoolean(false);

   private CountDownLatch latch;

   @PostConstruct
   public void startGrpcServer() {
      try {
         latch = new CountDownLatch(1);
         Server grpcServer = null;

         // If cert and private key is provided, build a TLS capable server.
         if (certChainFilePath != null && certChainFilePath.length() > 0
               && privateKeyFilePath != null && privateKeyFilePath.length() > 0) {
            TlsServerCredentials.Builder tlsBuilder = TlsServerCredentials.newBuilder()
                  .keyManager(new File(certChainFilePath), new File(privateKeyFilePath));

            try {
               grpcServer = Grpc.newServerBuilderForPort(serverPort, tlsBuilder.build())
                     .fallbackHandlerRegistry(mockHandlerRegistry)
                     .build();
            } catch (IllegalArgumentException iae) {
               if (iae.getCause() instanceof NoSuchAlgorithmException
                     || iae.getCause() instanceof InvalidKeySpecException) {

                  // Private key may be not directly recognized as the RSA keys generated by Helm chart genSelfSignedCert.
                  // Underlying Netty only supports PKCS#8 formmatted private key and key is detected as a key pair instead.
                  log.warn("GRPC PrivateKey appears to be invalid. Trying to convert it.");

                  final byte[] privateKeyBytes = extractPrivateKeyIfAny(privateKeyFilePath);
                  if (privateKeyBytes != null) {
                     log.info("Building a GRPC server with converted key");
                     tlsBuilder = TlsServerCredentials.newBuilder()
                           .keyManager(new FileInputStream(certChainFilePath),
                                 new ByteArrayInputStream(privateKeyBytes));
                     grpcServer = Grpc.newServerBuilderForPort(serverPort, tlsBuilder.build())
                           .fallbackHandlerRegistry(mockHandlerRegistry)
                           .build();
                  }
               }
            }
         } else {
            // Else build a "plain text" server.
            grpcServer = ServerBuilder.forPort(serverPort)
                  .fallbackHandlerRegistry(mockHandlerRegistry)
                  .build();
         }
         grpcServer.start();
         log.info("GRPC Server started on port " + serverPort);

         Server finalGrpcServer = grpcServer;
         Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
               try {
                  if (finalGrpcServer != null) {
                     log.info("Shutting down gRPC server since JVM is shutting down");
                     finalGrpcServer.shutdown().awaitTermination(2, TimeUnit.SECONDS);
                  }
               } catch (InterruptedException e) {
                  e.printStackTrace();
               }
            }
         });
         startDaemonAwaitThread();
      } catch (Exception e) {
         log.error("GRPC Server cannot be started", e);
      }
   }

   private void startDaemonAwaitThread() {
      Thread awaitThread = new Thread(() -> {
         try {
            isRunning.set(true);
            latch.await();
         } catch (InterruptedException e) {
            log.error("GRPC Server awaiter interrupted.", e);
         }finally {
            isRunning.set(false);
         }
      });
      awaitThread.setName("grpc-server-awaiter");
      awaitThread.setDaemon(false);
      awaitThread.start();
   }

   private static byte[] extractPrivateKeyIfAny(String privateKeyFilePath) throws IOException {
      String privateKey = new String(Files.readAllBytes(Path.of(privateKeyFilePath)), StandardCharsets.UTF_8);
      if (privateKey.startsWith(BEGIN_RSA_PRIVATE_KEY)) {
         PEMParser pemParser = new PEMParser(new FileReader(privateKeyFilePath));
         Object object = pemParser.readObject();
         pemParser.close();
         JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

         PrivateKey privatekey = null;

         log.debug("Parsed PrivateKey: {}", object);
         if (object instanceof PEMKeyPair) {
            privatekey = converter.getPrivateKey(((PEMKeyPair) object).getPrivateKeyInfo());
         }
         if (object instanceof PrivateKeyInfo) {
            privatekey = converter.getPrivateKey((PrivateKeyInfo) object);
         }
         if (privatekey != null) {
            log.debug("Found PrivateKey Algorithm: {}", privatekey.getAlgorithm()); // ex. RSA
            log.debug("Found PrivateKey Format: {}", privatekey.getFormat()); // ex. PKCS#8

            String privateKeyPem  = BEGIN_RSA_PRIVATE_KEY + "\n" +
                  Base64.getEncoder().encodeToString(privatekey.getEncoded()) + "\n" + END_RSA_PRIVATE_KEY + "\n";
            log.debug("New PrivateKey PEM is {}", privateKeyPem);
            return privateKeyPem.getBytes(StandardCharsets.UTF_8);
         }
      }
      return null;
   }
}
