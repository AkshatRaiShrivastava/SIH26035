package com.legalmetrology.rulesengine;
import java.math.*; import java.util.*;
public final class OimlR76RulesEngine implements RulesEngine {
 private static final MathContext MC=new MathContext(10,RoundingMode.HALF_UP); public static final String VERSION="r76-1.0.0";
 public CalculationOutput evaluateObservation(InstrumentSpec i, ObservationInput o, MpeLookupResult m, String testType) {
  Objects.requireNonNull(i); Objects.requireNonNull(o); Objects.requireNonNull(m); if(i.eValue().signum()<=0||i.dValue().signum()<=0||m.mpeValue().signum()<0) throw new IllegalArgumentException("Positive metrological values are required");
  int scale=i.dValue().stripTrailingZeros().scale(); BigDecimal error=o.indicatedValue().subtract(o.appliedLoad()).setScale(Math.max(0,scale),RoundingMode.HALF_UP);
  BigDecimal errorInE=error.divide(i.eValue(),MC); boolean pass=error.abs().compareTo(m.mpeValue())<=0;
  return new CalculationOutput(o.observationId(),error,errorInE,m.mpeRuleId(),m.mpeValue(),pass,VERSION);
 }
 public TestRollupResult rollUpTest(List<CalculationOutput> all) { if(all==null||all.isEmpty()) return new TestRollupResult("FAIL",List.of("No observations were evaluated")); List<String> reasons=new ArrayList<>(); for(CalculationOutput c:all) if(!c.withinTolerance()) reasons.add("Observation "+c.observationId()+" exceeded MPE by "+c.errorValue().abs().subtract(c.mpeValue()).stripTrailingZeros().toPlainString()); return new TestRollupResult(reasons.isEmpty()?"PASS":"FAIL",List.copyOf(reasons)); }
}
