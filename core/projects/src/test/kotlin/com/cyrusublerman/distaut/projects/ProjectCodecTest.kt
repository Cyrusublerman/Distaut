package com.cyrusublerman.distaut.projects

import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.model.ProjectState
import com.cyrusublerman.distaut.model.SourceAsset
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectCodecTest {
    @Test
    fun roundTripsProjectAndSourceMetadata() {
        val state = ProjectState(
            source = SourceAsset(
                uri = "content://image/1",
                displayName = "image.jpg",
                mimeType = "image/jpeg",
                width = 4000,
                height = 3000,
                checksum = "abc",
                persistedPermission = true,
                managedCopy = false,
            ),
            effects = listOf(
                EffectInstance(
                    id = "p",
                    type = "posterise",
                    parameters = mapOf("levels" to ParameterValue.Integer(5)),
                )
            ),
            soloEffectId = "p",
            globalSeed = 100,
            revision = 7,
        )
        val document = ProjectDocument(
            engineVersion = "test",
            savedAtEpochMillis = 123,
            project = state,
        )
        assertEquals(
            document,
            ProjectCodec.decode(ProjectCodec.encode(document), setOf("posterise")),
        )
    }
}
