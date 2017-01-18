package io.pivotal.security.service;

import com.greghaskins.spectrum.Spectrum;
import io.pivotal.security.data.EncryptionKeyCanaryDataService;
import io.pivotal.security.entity.EncryptionKeyCanary;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import java.security.Key;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static com.greghaskins.spectrum.Spectrum.beforeEach;
import static com.greghaskins.spectrum.Spectrum.describe;
import static com.greghaskins.spectrum.Spectrum.it;
import static io.pivotal.security.helper.SpectrumHelper.itThrowsWithMessage;
import static io.pivotal.security.service.EncryptionKeyCanaryMapper.CANARY_VALUE;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(Spectrum.class)
public class EncryptionKeyCanaryMapperTest {
  private EncryptionKeyCanaryMapper subject;
  private EncryptionKeyCanaryDataService encryptionKeyCanaryDataService;
  private EncryptionService encryptionService;
  private UUID activeCanaryUUID;
  private UUID existingCanaryUUID1;
  private UUID existingCanaryUUID2;
  private Key activeEncryptionKey;
  private Key existingEncryptionKey1;
  private Key existingEncryptionKey2;
  private EncryptionKeyCanary activeEncryptionKeyCanary;
  private EncryptionKeyCanary existingEncryptionKeyCanary1;
  private EncryptionKeyCanary existingEncryptionKeyCanary2;

  {
    beforeEach(() -> {
      encryptionKeyCanaryDataService = mock(EncryptionKeyCanaryDataService.class);
      encryptionService = mock(EncryptionService.class);

      activeCanaryUUID = UUID.randomUUID();

      activeEncryptionKey = mock(Key.class);
      when(encryptionService.getActiveKey()).thenReturn(activeEncryptionKey);

      activeEncryptionKeyCanary = createEncryptionCanary(activeCanaryUUID, "fake-active-encrypted-value", "fake-active-nonce", activeEncryptionKey);

      when(encryptionService.encrypt(activeEncryptionKey, CANARY_VALUE))
          .thenReturn(new Encryption("fake-encrypted-value".getBytes(), "fake-nonce".getBytes()));
    });

    describe("when there is no active key", () -> {
      beforeEach(() -> {
        when(encryptionService.getKeys()).thenReturn(asList());
      });

      itThrowsWithMessage("a warning about no active key", RuntimeException.class, "No active key was found", () -> {
        new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
      });
    });

    describe("when the active key is the only key", () -> {
      beforeEach(() -> {
        when(encryptionService.getKeys()).thenReturn(asList(activeEncryptionKey));
      });

      describe("when there are no canaries in the database", () -> {
        beforeEach(() -> {
          when(encryptionKeyCanaryDataService.findAll()).thenReturn(new ArrayList<>());

          when(encryptionKeyCanaryDataService.save(any(EncryptionKeyCanary.class)))
              .thenReturn(activeEncryptionKeyCanary);

          subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
        });

        it("creates and saves canary to the database", () -> {
          ArgumentCaptor<EncryptionKeyCanary> argumentCaptor = ArgumentCaptor.forClass(EncryptionKeyCanary.class);
          verify(encryptionKeyCanaryDataService, times(1)).save(argumentCaptor.capture());

          EncryptionKeyCanary encryptionKeyCanary = argumentCaptor.getValue();
          assertThat(encryptionKeyCanary.getEncryptedValue(), equalTo("fake-encrypted-value".getBytes()));
          assertThat(encryptionKeyCanary.getNonce(), equalTo("fake-nonce".getBytes()));
          verify(encryptionService, times(1)).encrypt(activeEncryptionKey, CANARY_VALUE);
        });

        it("returns a map between the new canary and the active key", () -> {
          Map<UUID, Key> encryptionKeyMap = subject.getEncryptionKeyMap();

          assertThat(encryptionKeyMap.entrySet().size(), equalTo(1));
          assertThat(encryptionKeyMap.get(activeCanaryUUID), equalTo(activeEncryptionKey));
        });

        it("sets the new canary's UUID as active", () -> {
          assertThat(subject.getActiveUuid(), equalTo(activeCanaryUUID));
        });
      });

      describe("when there is no matching canary in the database", () -> {
        EncryptionKeyCanary nonMatchingCanary = new EncryptionKeyCanary();

        beforeEach(() -> {
          nonMatchingCanary.setUuid(UUID.randomUUID());
          nonMatchingCanary.setEncryptedValue("fake-non-matching-encrypted-value".getBytes());
          nonMatchingCanary.setNonce("fake-non-matching-nonce".getBytes());

          when(encryptionKeyCanaryDataService.findAll()).thenReturn(Arrays.asList(nonMatchingCanary));
        });

        describe("when decrypting with the wrong key raises AEADBadTagException (dev_internal)", () -> {
          beforeEach(() -> {
            when(encryptionService.decrypt(activeEncryptionKey, nonMatchingCanary.getEncryptedValue(), nonMatchingCanary.getNonce()))
                .thenThrow(new AEADBadTagException());
            when(encryptionKeyCanaryDataService.save(any(EncryptionKeyCanary.class)))
                .thenReturn(activeEncryptionKeyCanary);

            subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
          });

          it("should create a canary for the key", () -> {
            ArgumentCaptor<EncryptionKeyCanary> argumentCaptor = ArgumentCaptor.forClass(EncryptionKeyCanary.class);
            verify(encryptionKeyCanaryDataService, times(1)).save(argumentCaptor.capture());

            EncryptionKeyCanary encryptionKeyCanary = argumentCaptor.getValue();
            assertThat(encryptionKeyCanary.getEncryptedValue(), equalTo("fake-encrypted-value".getBytes()));
            assertThat(encryptionKeyCanary.getNonce(), equalTo("fake-nonce".getBytes()));
            verify(encryptionService, times(1)).encrypt(activeEncryptionKey, CANARY_VALUE);
          });
        });

        describe("when decrypting with the wrong key raises a known IllegalBlockSizeException error (HSM)", () -> {
          beforeEach(() -> {
            when(encryptionService.decrypt(activeEncryptionKey, nonMatchingCanary.getEncryptedValue(), nonMatchingCanary.getNonce()))
                .thenThrow(new IllegalBlockSizeException("Could not process input data: function 'C_Decrypt' returns 0x40"));
            when(encryptionKeyCanaryDataService.save(any(EncryptionKeyCanary.class)))
                .thenReturn(activeEncryptionKeyCanary);

            subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
          });

          it("should create a canary for the key", () -> {
            ArgumentCaptor<EncryptionKeyCanary> argumentCaptor = ArgumentCaptor.forClass(EncryptionKeyCanary.class);
            verify(encryptionKeyCanaryDataService, times(1)).save(argumentCaptor.capture());

            EncryptionKeyCanary encryptionKeyCanary = argumentCaptor.getValue();
            assertThat(encryptionKeyCanary.getEncryptedValue(), equalTo("fake-encrypted-value".getBytes()));
            assertThat(encryptionKeyCanary.getNonce(), equalTo("fake-nonce".getBytes()));
            verify(encryptionService, times(1)).encrypt(activeEncryptionKey, CANARY_VALUE);
          });
        });

        describe("when decrypting with the wrong key raises an unknown IllegalBlockSizeException error (HSM)", () -> {
          beforeEach(() -> {
            when(encryptionService.decrypt(activeEncryptionKey, nonMatchingCanary.getEncryptedValue(), nonMatchingCanary.getNonce()))
                .thenThrow(new IllegalBlockSizeException("I don't know what 0x41 means and neither do you"));
            when(encryptionKeyCanaryDataService.save(any(EncryptionKeyCanary.class)))
                .thenReturn(activeEncryptionKeyCanary);
          });

          itThrowsWithMessage("something", RuntimeException.class, "javax.crypto.IllegalBlockSizeException: I don't know what 0x41 means and neither do you", () -> {
            subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
          });
        });

        describe("when decrypting with the wrong key raises a known BadPaddingException error (DSM)", () -> {
          beforeEach(() -> {
            when(encryptionService.decrypt(activeEncryptionKey, nonMatchingCanary.getEncryptedValue(), nonMatchingCanary.getNonce()))
                .thenThrow(new BadPaddingException("Decrypt error: rv=48"));
            when(encryptionKeyCanaryDataService.save(any(EncryptionKeyCanary.class)))
                .thenReturn(activeEncryptionKeyCanary);

            subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
          });

          it("should create a canary for the key", () -> {
            ArgumentCaptor<EncryptionKeyCanary> argumentCaptor = ArgumentCaptor.forClass(EncryptionKeyCanary.class);
            verify(encryptionKeyCanaryDataService, times(1)).save(argumentCaptor.capture());

            EncryptionKeyCanary encryptionKeyCanary = argumentCaptor.getValue();
            assertThat(encryptionKeyCanary.getEncryptedValue(), equalTo("fake-encrypted-value".getBytes()));
            assertThat(encryptionKeyCanary.getNonce(), equalTo("fake-nonce".getBytes()));
            verify(encryptionService, times(1)).encrypt(activeEncryptionKey, CANARY_VALUE);
          });
        });

        describe("when decrypting with the wrong key raises an unknown BadPaddingException error (DSM)", () -> {
          beforeEach(() -> {
            when(encryptionService.decrypt(activeEncryptionKey, nonMatchingCanary.getEncryptedValue(), nonMatchingCanary.getNonce()))
                .thenThrow(new BadPaddingException("Decrypt error: rv=1337 too cool for school"));
            when(encryptionKeyCanaryDataService.save(any(EncryptionKeyCanary.class)))
                .thenReturn(activeEncryptionKeyCanary);
          });

          itThrowsWithMessage("something", RuntimeException.class, "javax.crypto.BadPaddingException: Decrypt error: rv=1337 too cool for school", () -> {
            subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
          });
        });

        describe("when decrypting with the wrong key returns an incorrect canary value", () -> {
          beforeEach(() -> {
            when(encryptionService.decrypt(activeEncryptionKey, nonMatchingCanary.getEncryptedValue(), nonMatchingCanary.getNonce()))
                .thenReturn("different-canary-value");
            when(encryptionKeyCanaryDataService.save(any(EncryptionKeyCanary.class)))
                .thenReturn(activeEncryptionKeyCanary);

            subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
          });

          it("should create a canary for the key", () -> {
            ArgumentCaptor<EncryptionKeyCanary> argumentCaptor = ArgumentCaptor.forClass(EncryptionKeyCanary.class);
            verify(encryptionKeyCanaryDataService, times(1)).save(argumentCaptor.capture());

            EncryptionKeyCanary encryptionKeyCanary = argumentCaptor.getValue();
            assertThat(encryptionKeyCanary.getEncryptedValue(), equalTo("fake-encrypted-value".getBytes()));
            assertThat(encryptionKeyCanary.getNonce(), equalTo("fake-nonce".getBytes()));
            verify(encryptionService, times(1)).encrypt(activeEncryptionKey, CANARY_VALUE);
          });
        });
      });

      describe("when there is a matching canary in the database", () -> {
        beforeEach(() -> {
          when(encryptionKeyCanaryDataService.findAll()).thenReturn(asList(activeEncryptionKeyCanary));
          when(encryptionService.decrypt(activeEncryptionKey, activeEncryptionKeyCanary.getEncryptedValue(), activeEncryptionKeyCanary.getNonce()))
              .thenReturn(CANARY_VALUE);

          subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
        });

        it("should map the key to the matching canary", () -> {
          Map<UUID, Key> encryptionKeyMap = subject.getEncryptionKeyMap();

          assertThat(encryptionKeyMap.entrySet().size(), equalTo(1));
          assertThat(encryptionKeyMap.get(activeCanaryUUID), equalTo(activeEncryptionKey));
        });

        it("should not re-encrypt the canary value", () -> {
          verify(encryptionService, times(0)).encrypt(eq(activeEncryptionKey), any(String.class));
        });

        it("sets the matching canary's UUID as active", () -> {
          assertThat(subject.getActiveUuid(), equalTo(activeCanaryUUID));
        });
      });
    });

    describe("when there are multiple keys", () -> {
      beforeEach(() -> {
        existingCanaryUUID1 = UUID.randomUUID();
        existingCanaryUUID2 = UUID.randomUUID();

        existingEncryptionKey1 = mock(Key.class);
        existingEncryptionKey2 = mock(Key.class);

        when(encryptionService.getKeys()).thenReturn(asList(existingEncryptionKey1, activeEncryptionKey, existingEncryptionKey2));

        existingEncryptionKeyCanary1 = new EncryptionKeyCanary();
        existingEncryptionKeyCanary1.setUuid(existingCanaryUUID1);
        existingEncryptionKeyCanary1.setEncryptedValue("fake-existing-encrypted-value1".getBytes());
        existingEncryptionKeyCanary1.setNonce("fake-existing-nonce1".getBytes());
        when(encryptionService.decrypt(existingEncryptionKey1, "fake-existing-encrypted-value1".getBytes(), "fake-existing-nonce1".getBytes()))
            .thenReturn(CANARY_VALUE);
        
        existingEncryptionKeyCanary2 = new EncryptionKeyCanary();
        existingEncryptionKeyCanary2.setUuid(existingCanaryUUID2);
        existingEncryptionKeyCanary2.setEncryptedValue("fake-existing-encrypted-value2".getBytes());
        existingEncryptionKeyCanary2.setNonce("fake-existing-nonce2".getBytes());
        when(encryptionService.decrypt(existingEncryptionKey2, "fake-existing-encrypted-value2".getBytes(), "fake-existing-nonce2".getBytes()))
            .thenReturn(CANARY_VALUE);
      });

      describe("when there are matching canaries for all of the keys", () -> {
        beforeEach(() -> {
          when(encryptionKeyCanaryDataService.findAll()).thenReturn(asList(existingEncryptionKeyCanary1, activeEncryptionKeyCanary, existingEncryptionKeyCanary2));

          subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
        });

        it("should return a map between the matching canaries and keys", () -> {
          Map<UUID, Key> encryptionKeyMap = subject.getEncryptionKeyMap();

          assertThat(encryptionKeyMap.entrySet().size(), equalTo(3));
          assertThat(encryptionKeyMap.get(activeCanaryUUID), equalTo(activeEncryptionKey));
          assertThat(encryptionKeyMap.get(existingCanaryUUID1), equalTo(existingEncryptionKey1));
          assertThat(encryptionKeyMap.get(existingCanaryUUID2), equalTo(existingEncryptionKey2));
        });

        it("should set the active key's canary UUID as active", () -> {
          assertThat(subject.getActiveUuid(), equalTo(activeCanaryUUID));
        });
      });

      describe("when there is a non-active key that does not have a matching canary", () -> {
        beforeEach(() -> {
          when(encryptionKeyCanaryDataService.findAll()).thenReturn(asList(existingEncryptionKeyCanary1, activeEncryptionKeyCanary));

          subject = new EncryptionKeyCanaryMapper(encryptionKeyCanaryDataService, encryptionService);
        });

        it("should not create a canary for the key", () -> {
          verify(encryptionKeyCanaryDataService, times(0)).save(any(EncryptionKeyCanary.class));
        });

        it("should not include it in the returned map", () -> {
          Map<UUID, Key> encryptionKeyMap = subject.getEncryptionKeyMap();

          assertThat(encryptionKeyMap.entrySet().size(), equalTo(2));
          assertThat(encryptionKeyMap.get(activeCanaryUUID), equalTo(activeEncryptionKey));
          assertThat(encryptionKeyMap.get(existingCanaryUUID1), equalTo(existingEncryptionKey1));
        });

        it("should set the active key's canary UUID as active", () -> {
          assertThat(subject.getActiveUuid(), equalTo(activeCanaryUUID));
        });
      });
    });
  }

  private EncryptionKeyCanary createEncryptionCanary(UUID activeCanaryUUID, String encryptedValue, String nonce, Key encryptionKey)
      throws Exception {
    EncryptionKeyCanary encryptionKeyCanary = new EncryptionKeyCanary();
    encryptionKeyCanary.setUuid(activeCanaryUUID);
    encryptionKeyCanary.setEncryptedValue(encryptedValue.getBytes());
    encryptionKeyCanary.setNonce(nonce.getBytes());
    when(encryptionService.decrypt(encryptionKey, encryptedValue.getBytes(), nonce.getBytes()))
        .thenReturn(CANARY_VALUE);
    return encryptionKeyCanary;
  }
}