package com.stove.download.core.service;

import static com.stove.download.DownloadFixtures.build;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.stove.common.web.error.*;
import com.stove.download.core.domain.*;
import com.stove.download.core.port.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.mockito.InOrder;

class ReadBuildDownloadTest {
    OwnedProducts owned = mock(OwnedProducts.class);
    PublishedBuilds published = mock(PublishedBuilds.class);
    BuildStorage storage = mock(BuildStorage.class);
    ReadBuildDownload issue = new ReadBuildDownload(owned, published, storage);
    DownloadRequest request = new DownloadRequest("buyer", "game-one");

    @Test void checksOwnershipThenPublicationThenStorageAndReturnsSixValues() {
        when(owned.require("game-one", "token")).thenReturn(new OwnedProduct(1, "game-one"));
        when(published.require("game-one")).thenReturn(build());
        SignedUrl signed = new SignedUrl("https://storage.example/game.zip", Instant.now().plusSeconds(300));
        when(storage.download(build())).thenReturn(signed);
        DownloadTicket result = issue.execute(request, "token");
        assertThat(result).isEqualTo(DownloadTicket.of(build(), signed));
        InOrder order = inOrder(owned, published, storage);
        order.verify(owned).require("game-one", "token");
        order.verify(published).require("game-one");
        order.verify(storage).download(build());
        verifyNoMoreInteractions(owned, published, storage);
    }

    @Test void unownedOrUnavailableLicenseStopsBeforeCatalogAndStorage() {
        when(owned.require(anyString(), anyString())).thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> issue.execute(request, "token")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(published, storage);
    }

    @Test void wrongOwnedProductCodeStopsBeforeCatalog() {
        when(owned.require(anyString(), anyString())).thenReturn(new OwnedProduct(1, "other"));
        assertThatThrownBy(() -> issue.execute(request, "token")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
        verifyNoInteractions(published, storage);
    }

    @Test void mismatchedProductIdentityCannotSignAnotherProductsFile() {
        for (OwnedProduct product : new OwnedProduct[]{new OwnedProduct(2, "game-one"), new OwnedProduct(1, "other")}) {
            reset(owned, published, storage);
            when(owned.require(anyString(), anyString())).thenReturn(product);
            when(published.require(anyString())).thenReturn(build());
            assertThatThrownBy(() -> issue.execute(request, "token")).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE));
            verifyNoInteractions(storage);
        }
    }

    @Test void unavailableCatalogStopsBeforeStorage() {
        when(owned.require(anyString(), anyString())).thenReturn(new OwnedProduct(1, "game-one"));
        when(published.require(anyString())).thenThrow(new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE));
        assertThatThrownBy(() -> issue.execute(request, "token")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }
}
