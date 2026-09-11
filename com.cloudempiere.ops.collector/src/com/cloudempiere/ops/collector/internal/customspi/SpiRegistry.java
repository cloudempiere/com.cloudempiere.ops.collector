/******************************************************************************
 * Product: CloudEmpiere Operational Observability Collector                   *
 * Copyright (C) 2026 CloudEmpiere. All Rights Reserved.                       *
 * This program is free software; you can redistribute it and/or modify it     *
 * under the terms version 2 of the GNU General Public License as published    *
 * by the Free Software Foundation.                                            *
 *****************************************************************************/
package com.cloudempiere.ops.collector.internal.customspi;

import java.util.Collections;
import java.util.List;

/**
 * The catalog of extraction-work SPI seams that {@link CustomSpiHealthSource} treats as
 * <b>mandatory on every CloudEmpiere deployment</b>: {@code Core.getXxx()} accessors
 * (org.cloudempiere.base.Core, bundle {@code org.adempiere.base}) that resolve a plugin
 * implementation via {@code Service.locator()}. Since the 2026-08-06 "Option B" decision, each
 * accessor returns {@code null} — and the core call site falls back inline to vanilla/community
 * behavior — when nothing is registered. That fallback exists to keep {@code git subtree pull}
 * merge conflicts readable; it is not an acceptable steady-state in production, where every one of
 * these seams is expected to be backed by a real plugin implementation. This registry is what lets
 * {@link CustomSpiHealthSource} tell "correctly wired" apart from "silently running on vanilla
 * iDempiere behavior".
 *
 * <h2>Source of truth</h2>
 * The 83 entries below are transcribed from
 * {@code cloudempiere-workspace/docs/architecture/plugin_extraction/SPI-CALL-SITE-CONVENTION.md}
 * §5 ("Seam inventory"), with {@link RequiredSpi#interfaceFqcn()} resolved against
 * {@code Core.java}'s actual {@code import} statements (not re-derived from the doc's simple
 * names) so this list can't silently drift from the real class names. Per that doc's §8 ("Going
 * forward — new SPI introductions"), a newly-introduced seam must be added to §5 first, and then
 * mirrored here.
 *
 * <h2>Known gap — do not treat this list as exhaustive of every {@code Core.getXxx()} accessor</h2>
 * {@code Core.java} has 156 {@code public static I... getXxx()} accessors in total; only the 83
 * below are catalogued in SPI-CALL-SITE-CONVENTION.md §5 and vetted as extraction-work seams. The
 * other ~73 (e.g. {@code ICostDetailFactory}, {@code IInvoiceAllocationSupport}, and — likely a
 * pre-existing, legitimately-optional upstream extension point rather than a CLDE seam —
 * {@code IDMS}) have never been logged in that table, even though its own process says they
 * should be. Folding them into this "mandatory everywhere" registry without per-item triage against
 * upstream/git history would risk flagging a deliberately-optional accessor as a startup failure.
 * That triage is tracked as a follow-up (see {@code docs/specs/SPEC-custom-spi-health.md} "Open
 * questions") — do not silently extend this list to "all Core accessors" to close the gap.
 *
 * <h2>Verified-optional entries</h2>
 * {@code IDunningPersonResolver} is marked {@link RequiredSpi#optional()} — confirmed 2026-09-11 by
 * grepping every repo for {@code implements IDunningPersonResolver} and finding zero matches (the
 * only one of the 83 with none). Distinguish this from a real deployment gap: the banking seams
 * (e.g. {@code IPaymentCompletionGuard}) that initially looked the same way turned out to have real
 * implementations in {@code org.idempiere.banking} — that plugin just wasn't wired into one local
 * instance's launch config. "Has a null-safe fallback" does NOT by itself mean a seam is optional —
 * that shape is mandatory for all 83 per SPI-CALL-SITE-CONVENTION.md's Option B convention. Only
 * "zero implementers anywhere in the codebase" is evidence of genuine optionality; re-run that grep
 * before marking anything else optional, rather than assuming from the fallback pattern alone.
 *
 * @author CloudEmpiere
 */
public final class SpiRegistry {

	private static final List<RequiredSpi> SEAMS = Collections.unmodifiableList(java.util.Arrays.asList(
		new RequiredSpi("IAssetAdditionInvoiceLineGuard", "org.cloudempiere.assetaddition.spi.IAssetAdditionInvoiceLineGuard", "getAssetAdditionInvoiceLineGuard", List.of("MAssetAddition")),
		new RequiredSpi("IAttributeSetAllocationProvider", "com.cloudempiere.allocation.spi.IAttributeSetAllocationProvider", "getAttributeSetAllocationProvider", List.of()),
		new RequiredSpi("IAuthorizationTokenService", "org.cloudempiere.model.core.IAuthorizationTokenService", "getAuthorizationTokenService", List.of("AdempiereWebUI")),
		new RequiredSpi("IBankStatementBalanceEnsurer", "org.cloudempiere.banking.spi.IBankStatementBalanceEnsurer", "getBankStatementBalanceEnsurer", List.of("MBankStatement", "MBankStatementLine")),
		new RequiredSpi("IBankStatementBeginningBalancePolicy", "com.cloudempiere.banking.spi.IBankStatementBeginningBalancePolicy", "getBankStatementBeginningBalancePolicy", List.of("MBankStatement")),
		new RequiredSpi("IBankStatementLineCashTypePolicy", "com.cloudempiere.banking.spi.IBankStatementLineCashTypePolicy", "getBankStatementLineCashTypePolicy", List.of("MBankStatementLine")),
		new RequiredSpi("ICampaignStoreProvider", "org.cloudempiere.store.spi.ICampaignStoreProvider", "getCampaignStoreProvider", List.of()),
		new RequiredSpi("ICashPlanLineCalculator", "org.cloudempiere.banking.spi.ICashPlanLineCalculator", "getCashPlanLineCalculator", List.of("CalloutCashPlanLine", "MCashPlanLine", "MInvoice")),
		new RequiredSpi("IChangeLogSessionResolver", "com.cloudempiere.model.spi.IChangeLogSessionResolver", "getChangeLogSessionResolver", List.of("PO")),
		new RequiredSpi("IChartBuilderProvider", "org.cloudempiere.base.spi.IChartBuilderProvider", "getChartBuilderProvider", List.of("ChartRenderer", "ColumnLookup")),
		new RequiredSpi("IChartDatasourceValidationService", "com.cloudempiere.model.spi.IChartDatasourceValidationService", "getChartDatasourceValidationService", List.of("MChartDatasource")),
		new RequiredSpi("IChartModelService", "com.cloudempiere.model.spi.IChartModelService", "getChartModelService", List.of("CalloutChartDatasourceColumn", "ChartModel", "ChartParameter", "MChart")),
		new RequiredSpi("IChartRenderingService", "com.cloudempiere.model.spi.IChartRenderingService", "getChartRenderingService", List.of("MChart")),
		new RequiredSpi("IClientSetupPowerUserRolePolicy", "org.cloudempiere.setup.spi.IClientSetupPowerUserRolePolicy", "getClientSetupPowerUserRolePolicy", List.of()),
		new RequiredSpi("IClientSetupStorageOwnerPolicy", "org.cloudempiere.setup.spi.IClientSetupStorageOwnerPolicy", "getClientSetupStorageOwnerPolicy", List.of()),
		new RequiredSpi("ICostCurrentQtyProvider", "com.cloudempiere.costing.spi.ICostCurrentQtyProvider", "getCostCurrentQtyProvider", List.of("ProductCost")),
		new RequiredSpi("ICostDetailFailoverCostProvider", "com.cloudempiere.costing.spi.ICostDetailFailoverCostProvider", "getCostDetailFailoverCostProvider", List.of("MCostDetail", "ProductCost")),
		new RequiredSpi("ICostDetailNegativeQtyGuard", "com.cloudempiere.costing.spi.ICostDetailNegativeQtyGuard", "getCostDetailNegativeQtyGuard", List.of("MCostDetail")),
		new RequiredSpi("IDashboardContentFieldSyncService", "com.cloudempiere.model.spi.IDashboardContentFieldSyncService", "getDashboardContentFieldSyncService", List.of("MDashboardContent")),
		new RequiredSpi("IDashboardPreferenceSessionQueryProvider", "com.cloudempiere.model.spi.IDashboardPreferenceSessionQueryProvider", "getDashboardPreferenceSessionQueryProvider", List.of("MDashboardPreference")),
		new RequiredSpi("IDepositBatchWorkflowGuard", "com.cloudempiere.banking.spi.IDepositBatchWorkflowGuard", "getDepositBatchWorkflowGuard", List.of("MDepositBatch")),
		new RequiredSpi("IDepreciationCalculationEngine", "com.cloudempiere.fixedasset.spi.IDepreciationCalculationEngine", "getDepreciationCalculationEngine", List.of("MDepreciation")),
		new RequiredSpi("IDepreciationClassNameResolver", "org.cloudempiere.model.core.IDepreciationClassNameResolver", "getDepreciationClassNameResolver", List.of()),
		new RequiredSpi("IDepreciationScheduleBuilder", "com.cloudempiere.fixedasset.spi.IDepreciationScheduleBuilder", "getDepreciationScheduleBuilder", List.of("MDepreciationWorkfile")),
		new RequiredSpi("IDepreciationWorkfileMaintenance", "com.cloudempiere.fixedasset.spi.IDepreciationWorkfileMaintenance", "getDepreciationWorkfileMaintenance", List.of()),
		new RequiredSpi("IDiscountSchemaAttributeMatcher", "org.cloudempiere.pricing.spi.IDiscountSchemaAttributeMatcher", "getDiscountSchemaAttributeMatcher", List.of("MDiscountSchemaBreak")),
		new RequiredSpi("IDocTypeTaxProviderResolver", "com.cloudempiere.model.spi.IDocTypeTaxProviderResolver", "getDocTypeTaxProviderResolver", List.of("MInvoice", "MInvoiceLine", "MOrder", "MRMA", "MRMALine")),
		new RequiredSpi("IDocumentNoInternalSyncPolicy", "com.cloudempiere.model.spi.IDocumentNoInternalSyncPolicy", "getDocumentNoInternalSyncPolicy", List.of("PO")),
		RequiredSpi.optional("IDunningPersonResolver", "com.cloudempiere.model.spi.IDunningPersonResolver", "getDunningPersonResolver", List.of("MDunningRunEntry")),
		new RequiredSpi("IFreightLineReservationPolicy", "com.cloudempiere.order.spi.IFreightLineReservationPolicy", "getFreightLineReservationPolicy", List.of("MOrder")),
		new RequiredSpi("IHtmlEmailStyleInliner", "org.cloudempiere.util.IHtmlEmailStyleInliner", "getHtmlEmailStyleInliner", List.of("EMail")),
		new RequiredSpi("IHtmlImageColumnRenderer", "com.cloudempiere.print.spi.IHtmlImageColumnRenderer", "getHtmlImageColumnRenderer", List.of("HTMLReportRenderer")),
		new RequiredSpi("IInOutConfirmCompletionPolicy", "org.cloudempiere.wms.spi.IInOutConfirmCompletionPolicy", "getInOutConfirmCompletionPolicy", List.of("MInOutConfirm")),
		new RequiredSpi("IInOutConfirmInventoryDifferencePolicy", "org.cloudempiere.wms.spi.IInOutConfirmInventoryDifferencePolicy", "getInOutConfirmInventoryDifferencePolicy", List.of("MInOutConfirm")),
		new RequiredSpi("IInOutConfirmReactivationPolicy", "org.cloudempiere.wms.spi.IInOutConfirmReactivationPolicy", "getInOutConfirmReactivationPolicy", List.of("MInOutConfirm")),
		new RequiredSpi("IInOutConfirmSplitPackageCopier", "org.cloudempiere.wms.spi.IInOutConfirmSplitPackageCopier", "getInOutConfirmSplitPackageCopier", List.of("MInOutConfirm")),
		new RequiredSpi("IInOutLineConfirmQtyCorrector", "org.cloudempiere.wms.spi.IInOutLineConfirmQtyCorrector", "getInOutLineConfirmQtyCorrector", List.of("MInOutLineConfirm")),
		new RequiredSpi("IInOutLineDeleteGuardPolicy", "org.cloudempiere.wms.spi.IInOutLineDeleteGuardPolicy", "getInOutLineDeleteGuardPolicy", List.of("MInOutLine")),
		new RequiredSpi("IInOutLineLocatorFinder", "org.cloudempiere.wms.spi.IInOutLineLocatorFinder", "getInOutLineLocatorFinder", List.of("MInOutLine")),
		new RequiredSpi("IInOutLineMissingCostBaseProvider", "org.cloudempiere.wms.spi.IInOutLineMissingCostBaseProvider", "getInOutLineMissingCostBaseProvider", List.of("MInOutLine")),
		new RequiredSpi("IInOutLineOverDeliveryPolicy", "org.cloudempiere.wms.spi.IInOutLineOverDeliveryPolicy", "getInOutLineOverDeliveryPolicy", List.of("MInOutLine")),
		new RequiredSpi("IInOutLinePendingConfirmationPolicy", "org.cloudempiere.wms.spi.IInOutLinePendingConfirmationPolicy", "getInOutLinePendingConfirmationPolicy", List.of("MInOutLine")),
		new RequiredSpi("IInOutLineUomPolicy", "org.cloudempiere.wms.spi.IInOutLineUomPolicy", "getInOutLineUomPolicy", List.of("MInOutLine")),
		new RequiredSpi("IInvoiceAutoPaymentPolicy", "com.cloudempiere.invoice.spi.IInvoiceAutoPaymentPolicy", "getInvoiceAutoPaymentPolicy", List.of("MInvoice")),
		new RequiredSpi("IInvoiceBPStatisticsPolicy", "com.cloudempiere.invoice.spi.IInvoiceBPStatisticsPolicy", "getInvoiceBPStatisticsPolicy", List.of("MInvoice")),
		new RequiredSpi("IInvoiceBankAccountSyncPolicy", "com.cloudempiere.invoice.spi.IInvoiceBankAccountSyncPolicy", "getInvoiceBankAccountSyncPolicy", List.of("MInvoice")),
		new RequiredSpi("IInvoiceDateAcctPolicy", "com.cloudempiere.invoice.spi.IInvoiceDateAcctPolicy", "getInvoiceDateAcctPolicy", List.of("MInvoice")),
		new RequiredSpi("IInvoiceHeaderDefaultsPolicy", "com.cloudempiere.invoice.spi.IInvoiceHeaderDefaultsPolicy", "getInvoiceHeaderDefaultsPolicy", List.of("MInvoice", "MInvoiceLine")),
		new RequiredSpi("IInvoiceOrderAllocationPolicy", "com.cloudempiere.invoice.spi.IInvoiceOrderAllocationPolicy", "getInvoiceOrderAllocationPolicy", List.of("MInvoice")),
		new RequiredSpi("IInvoicePaymentTermSyncPolicy", "com.cloudempiere.invoice.spi.IInvoicePaymentTermSyncPolicy", "getInvoicePaymentTermSyncPolicy", List.of("MInvoice")),
		new RequiredSpi("ILocationStoreProvider", "org.cloudempiere.store.spi.ILocationStoreProvider", "getLocationStoreProvider", List.of("MBPartnerLocation", "MLocation")),
		new RequiredSpi("ILocatorValidationPolicy", "com.cloudempiere.model.spi.ILocatorValidationPolicy", "getLocatorValidationPolicy", List.of("MLocatorLookup")),
		new RequiredSpi("IMatchedPOCostResolver", "com.cloudempiere.costing.spi.IMatchedPOCostResolver", "getMatchedPOCostResolver", List.of("MCostDetail")),
		new RequiredSpi("IMeasurePeriodOffsetSqlProvider", "com.cloudempiere.model.spi.IMeasurePeriodOffsetSqlProvider", "getMeasurePeriodOffsetSqlProvider", List.of("MMeasureCalc")),
		new RequiredSpi("IMissingCostingRecordPolicy", "org.cloudempiere.wms.spi.IMissingCostingRecordPolicy", "getMissingCostingRecordPolicy", List.of("MInventoryLine")),
		new RequiredSpi("IMovementConfirmInventoryDifferencePolicy", "org.cloudempiere.wms.spi.IMovementConfirmInventoryDifferencePolicy", "getMovementConfirmInventoryDifferencePolicy", List.of("MMovementConfirm")),
		new RequiredSpi("IMovementLineAsiToAutoFillPolicy", "org.cloudempiere.wms.spi.IMovementLineAsiToAutoFillPolicy", "getMovementLineAsiToAutoFillPolicy", List.of("MMovementLine")),
		new RequiredSpi("IOrderPaymentRuleNormalizationPolicy", "com.cloudempiere.pricing.spi.IOrderPaymentRuleNormalizationPolicy", "getOrderPaymentRuleNormalizationPolicy", List.of()),
		new RequiredSpi("IOrderPriceListMandatoryPolicy", "com.cloudempiere.pricing.spi.IOrderPriceListMandatoryPolicy", "getOrderPriceListMandatoryPolicy", List.of()),
		new RequiredSpi("IOrderVolumeWeightService", "com.cloudempiere.order.spi.IOrderVolumeWeightService", "getOrderVolumeWeightService", List.of("MOrder")),
		new RequiredSpi("IPOSPaymentRoundingService", "org.cloudempiere.model.core.IPOSPaymentRoundingService", "getPOSPaymentRoundingService", List.of("Util")),
		new RequiredSpi("IPOSPaymentService", "com.cloudempiere.order.spi.IPOSPaymentService", "getPOSPaymentService", List.of("MOrder")),
		new RequiredSpi("IPackageConstructorDefaultsPolicy", "com.cloudempiere.shipping.spi.IPackageConstructorDefaultsPolicy", "getPackageConstructorDefaultsPolicy", List.of("MPackage")),
		new RequiredSpi("IPackageOriginDefaultsProvider", "com.cloudempiere.shipping.spi.IPackageOriginDefaultsProvider", "getPackageOriginDefaultsProvider", List.of("MPackage")),
		new RequiredSpi("IPackageShippingTransactionPolicy", "com.cloudempiere.shipping.spi.IPackageShippingTransactionPolicy", "getPackageShippingTransactionPolicy", List.of("MPackage")),
		new RequiredSpi("IPaymentCompletionGuard", "org.cloudempiere.banking.spi.IPaymentCompletionGuard", "getPaymentCompletionGuard", List.of("MPayment")),
		new RequiredSpi("IPaymentDefaultsPolicy", "org.cloudempiere.banking.spi.IPaymentDefaultsPolicy", "getPaymentDefaultsPolicy", List.of("MPayment")),
		new RequiredSpi("IPaymentPosPolicy", "com.cloudempiere.banking.spi.IPaymentPosPolicy", "getPaymentPosPolicy", List.of("MPayment")),
		new RequiredSpi("IPrintFormatClientCopyPolicy", "com.cloudempiere.print.spi.IPrintFormatClientCopyPolicy", "getPrintFormatClientCopyPolicy", List.of("ReportEngine")),
		new RequiredSpi("IProcessParameterDisplayService", "com.cloudempiere.model.spi.IProcessParameterDisplayService", "getProcessParameterDisplayService", List.of("MDashboardContent")),
		new RequiredSpi("IProductCategoryAncestorsPathService", "com.cloudempiere.product.spi.IProductCategoryAncestorsPathService", "getProductCategoryAncestorsPathService", List.of("MProductCategory")),
		new RequiredSpi("IProductCategorySaveGuard", "com.cloudempiere.product.spi.IProductCategorySaveGuard", "getProductCategorySaveGuard", List.of("MProductCategory")),
		new RequiredSpi("IProductStoreProvider", "org.cloudempiere.store.spi.IProductStoreProvider", "getProductStoreProvider", List.of("MProduct")),
		new RequiredSpi("IProductionStandardCostOverridePolicy", "com.cloudempiere.costing.spi.IProductionStandardCostOverridePolicy", "getProductionStandardCostOverridePolicy", List.of("MCostDetail")),
		new RequiredSpi("IProductionStorageOwnerPolicy", "org.cloudempiere.production.spi.IProductionStorageOwnerPolicy", "getProductionStorageOwnerPolicy", List.of("MProductionLine", "M_Production_Run")),
		new RequiredSpi("IReplenishHistoryPolicy", "org.cloudempiere.replenish.spi.IReplenishHistoryPolicy", "getReplenishHistoryPolicy", List.of()),
		new RequiredSpi("IReportDocTypeQueryHandler", "com.cloudempiere.print.spi.IReportDocTypeQueryHandler", "getReportDocTypeQueryHandler", List.of("ReportEngine")),
		new RequiredSpi("IRoleAccessTreeFilterService", "com.cloudempiere.model.spi.IRoleAccessTreeFilterService", "getRoleAccessTreeFilterService", List.of("MRole")),
		new RequiredSpi("IRoleConfirmQueryRecordsPolicy", "com.cloudempiere.model.spi.IRoleConfirmQueryRecordsPolicy", "getRoleConfirmQueryRecordsPolicy", List.of("MRole")),
		new RequiredSpi("IValueColumnSequenceOverridePolicy", "org.cloudempiere.model.core.IValueColumnSequenceOverridePolicy", "getValueColumnSequenceOverridePolicy", List.of("PO")),
		new RequiredSpi("IVocabularyCtl", "org.cloudempiere.vocabulary.IVocabularyCtl", "getIVocabularyCtl", List.of()),
		new RequiredSpi("IWarehouseStoreProvider", "org.cloudempiere.store.spi.IWarehouseStoreProvider", "getWarehouseStoreProvider", List.of()),
		new RequiredSpi("IWmsStorageOwnerPolicy", "org.cloudempiere.wms.spi.IWmsStorageOwnerPolicy", "getWmsStorageOwnerPolicy", List.of("InventoryCountCreate", "MInOut", "MInventory", "MMovement", "WPAttributeDialog"))
	));

	private SpiRegistry() {
	}

	/** The full catalog, immutable. */
	public static List<RequiredSpi> all() {
		return SEAMS;
	}
}
