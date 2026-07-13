package com.easybc.planner.sync.shared

import com.easybc.planner.sync.shared.ProfileRecord
import com.easybc.planner.sync.shared.SharedSyncState
import com.keyneom.synckit.sharing.SharingRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLabelsTest {
  private val profiles = listOf(
      ProfileRecord(
          datasetId = PRIMARY_DATASET_ID,
          ownerEmail = "mom@example.com",
          folderName = "EasyBC — mom@example.com",
          role = SharingRole.OWNER.name.lowercase(),
          trustedOwnerKeyId = "key-1",
      ),
      ProfileRecord(
          datasetId = "daughter",
          ownerEmail = "mom@example.com",
          folderName = "EasyBC — mom@example.com",
          displayName = "Daughter",
          role = SharingRole.OWNER.name.lowercase(),
          trustedOwnerKeyId = "key-1",
      ),
  )

  private val state = SharedSyncState(
      rpId = "keyneom.github.io",
      ownerEmail = "mom@example.com",
      activeProfileKey = "mom@example.com/primary",
      profiles = profiles,
  )

  @Test
  fun slugifyDatasetId_normalizesNames() {
      assertEquals("daughter", slugifyDatasetId("Daughter"))
      assertEquals("sarah-s-cycle", slugifyDatasetId("Sarah's cycle"))
  }

  @Test
  fun uniqueOwnedDatasetId_appendsSuffixOnCollision() {
      assertEquals("daughter-2", uniqueOwnedDatasetId("Daughter", "mom@example.com", profiles))
  }

  @Test
  fun newOwnedDatasetId_isOpaque() {
      assertEquals(
          "p-12345678-1234-1234-1234-123456789abc",
          newOwnedDatasetId(listOf("primary")) {
              "12345678-1234-1234-1234-123456789abc"
          },
      )
  }

  @Test
  fun profileDisplayLabel_usesDisplayNameForOwnedProfiles() {
      assertEquals("My data", profileDisplayLabel(state, profiles[0]))
      assertEquals("Daughter", profileDisplayLabel(state, profiles[1]))
  }

  @Test
  fun profileDisplayLabel_usesLocalOverrideAndDisambiguatesByOwner() {
      val first = profiles[1].copy(ownerEmail = "first@example.com", localDisplayName = "Alex")
      val second = profiles[1].copy(ownerEmail = "second@example.com", localDisplayName = "Alex")
      val value = state.copy(profiles = listOf(first, second))
      assertEquals("Alex", profileDisplayLabel(value, first))
      assertEquals("Alex — first@example.com", disambiguatedProfileLabel(value, first))
      assertEquals("Alex — second@example.com", disambiguatedProfileLabel(value, second))
  }

  @Test
  fun slugifyDatasetId_rejectsPrimary() {
      assertThrows(IllegalArgumentException::class.java) {
          slugifyDatasetId("primary")
      }
  }

  @Test
  fun duplicatePrimaryDatasetIds_areScopedByOwner() {
      val shared = ProfileRecord(
          datasetId = PRIMARY_DATASET_ID,
          ownerEmail = "other@example.com",
          folderName = "EasyBC — other@example.com",
          role = SharingRole.WRITER.name.lowercase(),
          trustedOwnerKeyId = "key-2",
          needsInitialLoad = true,
      )
      val value = state.copy(profiles = profiles + shared)
      assertEquals(profiles[0], findProfile(value, "mom@example.com/primary"))
      assertEquals(shared, findProfile(value, "other@example.com/primary"))
      assertTrue(shouldLoadRemoteBeforePublish(shared))
  }

  @Test
  fun meaningfulLocalData_requiresPreservationBeforeJoin() {
      assertFalse(hasMeaningfulSharedData(emptySharedPayload()))
      assertTrue(
          hasMeaningfulSharedData(
              emptySharedPayload().copy(
                  planner = emptySharedPayload().planner.copy(configured = true),
              ),
          ),
      )
  }

  @Test
  fun localProfile_isNamedAndNeverRequiresRemoteLoad() {
      val local = ProfileRecord(
          datasetId = "profile",
          ownerEmail = "local-123",
          folderName = "",
          displayName = "Offline journal",
          role = SharingRole.OWNER.name.lowercase(),
          trustedOwnerKeyId = "",
          syncMode = "local",
      )
      assertTrue(isLocalProfile(local))
      assertFalse(shouldLoadRemoteBeforePublish(local))
      assertEquals("Offline journal", profileDisplayLabel(state.copy(profiles = listOf(local)), local))
  }
}
