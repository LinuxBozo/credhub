package org.cloudfoundry.credhub.config;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@RunWith(JUnit4.class)
public class EncryptionKeyProviderTest {

  @Test
  public void initializesWithAnEmptyButAppendableKeyList() {
    EncryptionKeyProvider provider = new EncryptionKeyProvider();
    assertThat(provider.getKeys(), is(empty()));
    provider.getKeys().add(new EncryptionKeyMetadata());
    assertThat(provider.getKeys(), hasSize(1));
  }
}
