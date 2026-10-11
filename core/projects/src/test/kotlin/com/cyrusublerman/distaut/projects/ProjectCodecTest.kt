package com.cyrusublerman.distaut.projects

import com.cyrusublerman.distaut.model.EffectInstance
import com.cyrusublerman.distaut.model.ParameterValue
import com.cyrusublerman.distaut.model.ProjectState
import com.cyrusublerman.distaut.model.SourceAsset
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectCodecTest {
    @Test fun graphProjectRoundTripKeepsBranchesAndPositions() {
        val a = EffectInstance("a", "invert")
        val b = EffectInstance("b", "greyscale")
        val graph = com.cyrusublerman.distaut.model.ProcessingGraph(connections = listOf(
            com.cyrusublerman.distaut.model.GraphConnection("@source", "a", "image"),
            com.cyrusublerman.distaut.model.GraphConnection("@source", "b", "image")), outputNodeId = "a",
            positions = mapOf("b" to com.cyrusublerman.distaut.model.NodePosition(30f, 120f)))
        val document = ProjectDocument(engineVersion = "test", savedAtEpochMillis = 1,
            project = ProjectState(effects = listOf(a,b), graph = graph))
        assertEquals(document, ProjectCodec.decode(ProjectCodec.encode(document), setOf("invert", "greyscale")))
    }

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
