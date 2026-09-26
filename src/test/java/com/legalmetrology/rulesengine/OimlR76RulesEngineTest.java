package com.legalmetrology.rulesengine;
import static org.junit.jupiter.api.Assertions.*; import java.math.*; import java.util.*; import org.junit.jupiter.api.Test;
class OimlR76RulesEngineTest {
 private final OimlR76RulesEngine engine=new OimlR76RulesEngine(); private final RulesEngine.InstrumentSpec spec=new RulesEngine.InstrumentSpec("III",new BigDecimal("1000"),BigDecimal.ZERO,new BigDecimal("1.0"),new BigDecimal("0.1")); private final UUID rule=UUID.randomUUID();
 private RulesEngine.CalculationOutput eval(String indicated,String mpe){ return engine.evaluateObservation(spec,new RulesEngine.ObservationInput(UUID.randomUUID(),"WEIGHING_TEST",new BigDecimal("100.0"),new BigDecimal(indicated),null),new RulesEngine.MpeLookupResult(rule,new BigDecimal(mpe)),"INITIAL_VERIFICATION"); }
 @Test void cleanPassAndNegativeError(){ assertTrue(eval("100.3","0.5").withinTolerance()); assertTrue(eval("99.5","0.5").withinTolerance()); }
 @Test void inclusiveBoundaryAndOverBoundary(){ assertTrue(eval("100.5","0.5").withinTolerance()); assertFalse(eval("100.6","0.5").withinTolerance()); }
 @Test void roundsBeforeComparisonHalfUp(){ var r=eval("100.46","0.4"); assertEquals(new BigDecimal("0.5"),r.errorValue()); assertFalse(r.withinTolerance()); }
 @Test void rollupFailsOnAnyFailure(){ var pass=eval("100.2","0.5"); var fail=eval("101.0","0.5"); var result=engine.rollUpTest(List.of(pass,fail)); assertEquals("FAIL",result.verdict()); assertEquals(1,result.failureReasons().size()); }
 @Test void allPassRollsUp(){ assertEquals("PASS",engine.rollUpTest(List.of(eval("100.2","0.5"),eval("99.8","0.5"))).verdict()); }
}
