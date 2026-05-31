/**
 * Stable public contract of the CloudEmpiere Operational Observability Collector.
 * <p>
 * This is the ONLY exported package (ADR-001 §4.2). Consumers — deploy-dashboard-bff,
 * CLDE-Hub, the bridge Lambda, and other in-JVM bundles listening on OSGi EventAdmin —
 * depend exclusively on the types here ({@link com.cloudempiere.ops.collector.api.OpsEvent}
 * envelope and {@link com.cloudempiere.ops.collector.api.Severity} tiers). Everything
 * under {@code com.cloudempiere.ops.collector.internal} is implementation detail and
 * MUST NOT be imported by other bundles.
 *
 * @author CloudEmpiere
 */
package com.cloudempiere.ops.collector.api;
