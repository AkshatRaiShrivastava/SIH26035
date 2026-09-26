package com.legalmetrology.rulesengine;
import java.math.BigDecimal; import java.util.*;
public interface RulesEngine {
 CalculationOutput evaluateObservation(InstrumentSpec instrument, ObservationInput observation, MpeLookupResult mpe, String testType);
 TestRollupResult rollUpTest(List<CalculationOutput> allCalculationsInSession);
 record InstrumentSpec(String accuracyClassSymbol,BigDecimal maxCapacity,BigDecimal minCapacity,BigDecimal eValue,BigDecimal dValue){}
 record ObservationInput(UUID observationId,String testProcedure,BigDecimal appliedLoad,BigDecimal indicatedValue,String loadPosition){}
 record MpeLookupResult(UUID mpeRuleId,BigDecimal mpeValue){}
 record CalculationOutput(UUID observationId,BigDecimal errorValue,BigDecimal errorInE,UUID applicableMpeId,BigDecimal mpeValue,boolean withinTolerance,String engineVersion){}
 record TestRollupResult(String verdict,List<String> failureReasons){}
}
