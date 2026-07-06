package com.easybc.planner.sync.shared

import com.easybc.planner.sync.shared.ProfileRecord
import com.easybc.planner.sync.shared.SharedSyncState
import com.keyneom.synckit.sharing.SharingRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
  fun profileDisplayLabel_usesDisplayNameForOwnedProfiles() {
      assertEquals("My data", profileDisplayLabel(state, profiles[0]))
      assertEquals("Daughter", profileDisplayLabel(state, profiles[1]))
  }

  @Test
  fun slugifyDatasetId_rejectsPrimary() {
      assertThrows(IllegalArgumentException::class.java) {
          slugifyDatasetId("primary")
      }
  }
}
