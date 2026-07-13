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
            source = SourceAsset("content://image/1","image.jpg","image/jpeg",4000,3000,"abc",persistedPermission=true),
            effects = listOf(EffectInstance("p","posterise",parameters=mapOf("levels" to ParameterValue.Integer(5)))),
            soloEffectId = "p",
            globalSeed = Long.MAX_VALUE - 1,
            revision = 7,
        )
        val document = ProjectDocument(engineVersion="test",savedAtEpochMillis=Long.MAX_VALUE - 2,project=state)
        assertEquals(document, ProjectCodec.decode(ProjectCodec.encode(document), setOf("posterise")))
    }
}
