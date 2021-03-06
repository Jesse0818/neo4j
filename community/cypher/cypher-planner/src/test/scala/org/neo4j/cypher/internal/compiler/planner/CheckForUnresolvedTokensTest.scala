/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner

import org.neo4j.cypher.internal.ast.Query
import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.compiler.MissingLabelNotification
import org.neo4j.cypher.internal.compiler.MissingPropertyNameNotification
import org.neo4j.cypher.internal.compiler.MissingRelTypeNotification
import org.neo4j.cypher.internal.compiler.Neo4jCypherExceptionFactory
import org.neo4j.cypher.internal.compiler.phases.LogicalPlanState
import org.neo4j.cypher.internal.compiler.test_helpers.ContextHelper
import org.neo4j.cypher.internal.frontend.phases.RecordingNotificationLogger
import org.neo4j.cypher.internal.planner.spi.IDPPlannerName
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.InternalNotification
import org.neo4j.cypher.internal.util.LabelId
import org.neo4j.cypher.internal.util.PropertyKeyId
import org.neo4j.cypher.internal.util.RelTypeId
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite
import org.neo4j.values.storable.DurationFields
import org.neo4j.values.storable.PointFields
import org.neo4j.values.storable.TemporalValue.TemporalFields

import scala.collection.JavaConverters.asScalaSetConverter

class CheckForUnresolvedTokensTest extends CypherFunSuite with AstRewritingTestSupport with LogicalPlanConstructionTestSupport {

  test("warn when missing label") {
    //given
    val semanticTable = new SemanticTable

    //when
    val ast = parse("MATCH (a:A)-->(b:B) RETURN *")

    //then
    val notifications = checkForTokens(ast, semanticTable)

    notifications should equal(Set(
      MissingLabelNotification(InputPosition(9, 1, 10), "A"),
      MissingLabelNotification(InputPosition(17, 1, 18), "B")))
  }

  test("don't warn when labels are there") {
    //given
    val semanticTable = new SemanticTable
    semanticTable.resolvedLabelNames.put("A", LabelId(42))
    semanticTable.resolvedLabelNames.put("B", LabelId(84))

    //when
    val ast = parse("MATCH (a:A)-->(b:B) RETURN *")

    //then
    checkForTokens(ast, semanticTable) shouldBe empty
  }

  test("warn when missing relationship type") {
    //given
    val semanticTable = new SemanticTable
    semanticTable.resolvedLabelNames.put("A", LabelId(42))
    semanticTable.resolvedLabelNames.put("B", LabelId(84))

    //when
    val ast = parse("MATCH (a:A)-[r:R1|R2]->(b:B) RETURN *")

    //then
    checkForTokens(ast, semanticTable) should equal(Set(
      MissingRelTypeNotification(InputPosition(15, 1, 16), "R1"),
      MissingRelTypeNotification(InputPosition(18, 1, 19), "R2")))
  }

  test("don't warn when relationship types are there") {
    //given
    val semanticTable = new SemanticTable
    semanticTable.resolvedLabelNames.put("A", LabelId(42))
    semanticTable.resolvedLabelNames.put("B", LabelId(84))
    semanticTable.resolvedRelTypeNames.put("R1", RelTypeId(1))
    semanticTable.resolvedRelTypeNames.put("R2", RelTypeId(2))

    //when
    val ast = parse("MATCH (a:A)-[r:R1|R2]->(b:B) RETURN *")

    //then
    checkForTokens(ast, semanticTable) shouldBe empty
  }

  test("warn when missing property key name") {
    //given
    val semanticTable = new SemanticTable

    //when
    val ast = parse("MATCH (a) WHERE a.prop = 42 RETURN a")

    //then
    checkForTokens(ast, semanticTable) should equal(Set(
      MissingPropertyNameNotification(InputPosition(18, 1, 19), "prop")))
  }

  test("don't warn when property key name is there") {
    //given
    val semanticTable = new SemanticTable
    semanticTable.resolvedPropertyKeyNames.put("prop", PropertyKeyId(42))

    //when
    val ast = parse("MATCH (a {prop: 42}) RETURN a")

    //then
    checkForTokens(ast, semanticTable) shouldBe empty
  }

  test("don't warn for literal maps") {
    //given
    val semanticTable = new SemanticTable

    //when
    val ast = parse("RETURN {prop: 'foo'}")

    //then
    checkForTokens(ast, semanticTable) shouldBe empty
  }

  test("don't warn when using point properties") {
    //given
    val semanticTable = new SemanticTable
    semanticTable.resolvedPropertyKeyNames.put("prop", PropertyKeyId(42))

    PointFields.values().foreach { property =>
      //when
      val ast = parse(s"MATCH (a) WHERE point(a.prop).${property.propertyKey} = 42 RETURN a")

      //then
      checkForTokens(ast, semanticTable) shouldBe empty
    }
  }

  test("don't warn when using temporal properties") {
    //given
    val semanticTable = new SemanticTable
    semanticTable.resolvedPropertyKeyNames.put("prop", PropertyKeyId(42))

    TemporalFields.allFields().asScala.foreach { property =>
      //when
      val ast = parse(s"MATCH (a) WHERE date(a.prop).$property = 42 RETURN a")

      //then
      checkForTokens(ast, semanticTable) shouldBe empty
    }
  }

  test("don't warn when using duration properties") {
    //given
    val semanticTable = new SemanticTable
    semanticTable.resolvedPropertyKeyNames.put("prop", PropertyKeyId(42))

    DurationFields.values().foreach { property =>
      //when
      val ast = parse(s"MATCH (a) WHERE duration(a.prop).${property.propertyKey} = 42 RETURN a")

      //then
      checkForTokens(ast, semanticTable) shouldBe empty
    }
  }

  test("don't warn when using special property keys, independent of case") {
    //given
    val semanticTable = new SemanticTable
    semanticTable.resolvedPropertyKeyNames.put("prop", PropertyKeyId(42))

    Seq("X", "yEaRs", "DAY", "epochMillis").foreach { property =>
      //when
      val ast = parse(s"MATCH (a) WHERE a.prop.$property = 42 RETURN a")
      //then
      checkForTokens(ast, semanticTable) shouldBe empty
    }
  }

  private def checkForTokens(ast: Query, semanticTable: SemanticTable): Set[InternalNotification] = {
    val notificationLogger = new RecordingNotificationLogger
    val compilationState = LogicalPlanState(queryText = "apa",
      startPosition = None,
      plannerName = IDPPlannerName,
      newStubbedPlanningAttributes,
      maybeStatement = Some(ast),
      maybeSemanticTable = Some(semanticTable))
    val context = ContextHelper.create(notificationLogger = notificationLogger)
    CheckForUnresolvedTokens.transform(compilationState, context)
    notificationLogger.notifications
  }

  private def parse(query: String): Query = parser.parse(query, Neo4jCypherExceptionFactory(query, None)) match {
    case q: Query => q
    case _ => fail("Must be a Query")
  }

}
