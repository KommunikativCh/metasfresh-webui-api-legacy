package de.metas.ui.web.handlingunits;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.adempiere.ad.dao.IQueryBuilder;
import org.adempiere.ad.service.IADReferenceDAO;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.GuavaCollectors;
import org.adempiere.util.Services;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Product;
import org.compiere.util.Env;
import org.slf4j.Logger;

import de.metas.handlingunits.IHandlingUnitsBL;
import de.metas.handlingunits.IHandlingUnitsDAO;
import de.metas.handlingunits.exceptions.HUException;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.model.I_M_HU_Storage;
import de.metas.handlingunits.model.X_M_HU;
import de.metas.handlingunits.model.X_M_HU_PI_Version;
import de.metas.handlingunits.storage.IHUProductStorage;
import de.metas.handlingunits.storage.IHUStorage;
import de.metas.handlingunits.storage.IHUStorageFactory;
import de.metas.inoutcandidate.model.I_M_ReceiptSchedule;
import de.metas.logging.LogManager;
import de.metas.ui.web.handlingunits.util.HUPackingInfoFormatter;
import de.metas.ui.web.handlingunits.util.HUPackingInfos;
import de.metas.ui.web.view.DocumentViewCreateRequest;
import de.metas.ui.web.window.datatypes.DocumentId;
import de.metas.ui.web.window.datatypes.WindowId;
import de.metas.ui.web.window.datatypes.json.JSONLookupValue;

/*
 * #%L
 * metasfresh-webui-api
 * %%
 * Copyright (C) 2017 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public class HUDocumentViewLoader
{
	public static final HUDocumentViewLoader of(final DocumentViewCreateRequest request, final String referencingTableName)
	{
		return new HUDocumentViewLoader(request, referencingTableName);
	}

	private static final transient Logger logger = LogManager.getLogger(HUDocumentViewLoader.class);

	private final WindowId windowId;
	private final String referencingTableName;
	private final CopyOnWriteArraySet<Integer> huIds = new CopyOnWriteArraySet<>();

	private final HUDocumentViewAttributesProvider _attributesProvider;

	private HUDocumentViewLoader(final DocumentViewCreateRequest request, final String referencingTableName)
	{
		super();

		windowId = request.getWindowId();
		this.referencingTableName = referencingTableName;

		final Set<Integer> filterOnlyIds = request.getFilterOnlyIds();
		if (filterOnlyIds != null && !filterOnlyIds.isEmpty())
		{
			huIds.addAll(filterOnlyIds);
		}

		if (huIds.isEmpty())
		{
			throw new IllegalArgumentException("No filters specified for " + request);
		}

		_attributesProvider = new HUDocumentViewAttributesProvider();
	}

	public HUDocumentViewAttributesProvider getAttributesProvider()
	{
		return _attributesProvider;
	}

	public void addHUs(final Collection<I_M_HU> husToAdd)
	{
		final Set<Integer> huIdsToAdd = husToAdd.stream()
				.map(I_M_HU::getM_HU_ID)
				.collect(GuavaCollectors.toImmutableSet());

		this.huIds.addAll(huIdsToAdd);
	}

	public void removeHUs(final Collection<I_M_HU> husToRemove)
	{
		final Set<Integer> huIdsToRemove = husToRemove.stream()
				.map(I_M_HU::getM_HU_ID)
				.collect(GuavaCollectors.toImmutableSet());

		this.huIds.removeAll(huIdsToRemove);
	}

	public List<HUDocumentView> retrieveDocumentViews()
	{
		return retrieveTopLevelHUs(huIds)
				.stream()
				.map(hu -> createDocumentView(hu))
				.collect(GuavaCollectors.toImmutableList());
	}

	/**
	 * Retrieves the {@link HUDocumentView} hierarchy for given M_HU_ID, even if that M_HU_ID is not in scope.
	 * 
	 * @param huId
	 * @return {@link HUDocumentView} or null if the huId negative or zero.
	 */
	public HUDocumentView retrieveForHUId(final int huId)
	{
		if (huId <= 0)
		{
			return null;
		}
		final I_M_HU hu = InterfaceWrapperHelper.create(Env.getCtx(), huId, I_M_HU.class, ITrx.TRXNAME_None);
		return createDocumentView(hu);
	}

	private static List<I_M_HU> retrieveTopLevelHUs(final Collection<Integer> filterOnlyIds)
	{
		final IQueryBuilder<I_M_HU> queryBuilder = Services.get(IHandlingUnitsDAO.class)
				.createHUQueryBuilder()
				.setContext(Env.getCtx(), ITrx.TRXNAME_None)
				.setOnlyTopLevelHUs()
				.createQueryBuilder();

		if (filterOnlyIds != null && !filterOnlyIds.isEmpty())
		{
			queryBuilder.addInArrayFilter(I_M_HU.COLUMN_M_HU_ID, filterOnlyIds);
		}

		return queryBuilder
				.create()
				.list();
	}

	private HUDocumentView createDocumentView(final I_M_HU hu)
	{
		final boolean aggregatedTU = Services.get(IHandlingUnitsBL.class).isAggregateHU(hu);

		final String huUnitTypeCode = hu.getM_HU_PI_Version().getHU_UnitType();
		final HUDocumentViewType huRecordType;
		if (aggregatedTU)
		{
			huRecordType = HUDocumentViewType.TU;
		}
		else
		{
			huRecordType = HUDocumentViewType.ofHU_UnitType(huUnitTypeCode);
		}
		final String huUnitTypeDisplayName = huRecordType.getName();
		final JSONLookupValue huUnitTypeLookupValue = JSONLookupValue.of(huUnitTypeCode, huUnitTypeDisplayName);

		final JSONLookupValue huStatus = createHUStatusLookupValue(hu);
		final boolean processed = extractProcessed(hu);
		final int huId = hu.getM_HU_ID();

		final HUDocumentView.Builder huViewRecord = HUDocumentView.builder(windowId)
				.setDocumentId(DocumentId.of(huId))
				.setType(huRecordType)
				.setAttributesProvider(getAttributesProvider())
				.setProcessed(processed)
				//
				.setHUId(huId)
				.setCode(hu.getValue())
				.setHUUnitType(huUnitTypeLookupValue)
				.setHUStatus(huStatus)
				.setPackingInfo(extractPackingInfo(hu, huRecordType));

		//
		// Product/UOM/Qty if there is only one product stored
		final IHUProductStorage singleProductStorage = getSingleProductStorage(hu);
		if (singleProductStorage != null)
		{
			huViewRecord
					.setProduct(createProductLookupValue(singleProductStorage.getM_Product()))
					.setUOM(createUOMLookupValue(singleProductStorage.getC_UOM()))
					.setQtyCU(singleProductStorage.getQty());
		}

		//
		// Included HUs
		if (aggregatedTU)
		{
			final IHUStorageFactory storageFactory = Services.get(IHandlingUnitsBL.class).getStorageFactory();
			storageFactory
					.getStorage(hu)
					.getProductStorages()
					.stream()
					.map(huStorage -> createDocumentView(huId, huStorage, processed))
					.forEach(huViewRecord::addIncludedDocument);

		}
		else if (X_M_HU_PI_Version.HU_UNITTYPE_LoadLogistiqueUnit.equals(huUnitTypeCode))
		{
			final IHandlingUnitsDAO handlingUnitsDAO = Services.get(IHandlingUnitsDAO.class);
			handlingUnitsDAO.retrieveIncludedHUs(hu)
					.stream()
					.map(includedHU -> createDocumentView(includedHU))
					.forEach(huViewRecord::addIncludedDocument);
		}
		else if (X_M_HU_PI_Version.HU_UNITTYPE_TransportUnit.equals(huUnitTypeCode))
		{
			final IHandlingUnitsDAO handlingUnitsDAO = Services.get(IHandlingUnitsDAO.class);
			final IHUStorageFactory storageFactory = Services.get(IHandlingUnitsBL.class).getStorageFactory();
			handlingUnitsDAO.retrieveIncludedHUs(hu)
					.stream()
					.map(includedVHU -> storageFactory.getStorage(includedVHU))
					.flatMap(vhuStorage -> vhuStorage.getProductStorages().stream())
					.map(vhuProductStorage -> createDocumentView(huId, vhuProductStorage, processed))
					.forEach(huViewRecord::addIncludedDocument);
		}
		else if (X_M_HU_PI_Version.HU_UNITTYPE_VirtualPI.equals(huUnitTypeCode))
		{
			// do nothing
		}
		else
		{
			throw new HUException("Unknown HU_UnitType=" + huUnitTypeCode + " for " + hu);
		}

		return huViewRecord.build();
	}

	private static final String extractPackingInfo(final I_M_HU hu, final HUDocumentViewType huUnitType)
	{
		if (!huUnitType.isPureHU())
		{
			return "";
		}
		if (huUnitType == HUDocumentViewType.VHU)
		{
			return "";
		}

		try
		{
			return HUPackingInfoFormatter.newInstance()
					.setShowLU(true)
					.format(HUPackingInfos.of(hu));
		}
		catch (Exception ex)
		{
			logger.warn("Failed extracting packing info for {}", hu, ex);
			return "?";
		}
	}

	private final boolean extractProcessed(final I_M_HU hu)
	{
		//
		// Receipt schedule => consider the HU as processed if is not Planning (FIXME HARDCODED)
		if (I_M_ReceiptSchedule.Table_Name.equals(referencingTableName))
		{
			return !X_M_HU.HUSTATUS_Planning.equals(hu.getHUStatus());
		}
		else
		{
			return false;
		}
	}

	private IHUProductStorage getSingleProductStorage(final I_M_HU hu)
	{
		final IHUStorage huStorage = Services.get(IHandlingUnitsBL.class).getStorageFactory()
				.getStorage(hu);

		final I_M_Product product = huStorage.getSingleProductOrNull();
		if (product == null)
		{
			return null;
		}

		final IHUProductStorage productStorage = huStorage.getProductStorage(product);
		return productStorage;
	}

	private HUDocumentView createDocumentView(final int parent_HU_ID, final IHUProductStorage huStorage, final boolean processed)
	{
		final I_M_HU hu = huStorage.getM_HU();
		final int huId = hu.getM_HU_ID();

		final I_M_Product product = huStorage.getM_Product();

		final JSONLookupValue huUnitTypeLookupValue = JSONLookupValue.of(X_M_HU_PI_Version.HU_UNITTYPE_VirtualPI, "CU");
		final JSONLookupValue huStatus = createHUStatusLookupValue(hu);

		final HUDocumentView.Builder storageDocumentBuilder = HUDocumentView.builder(windowId)
				.setDocumentId(DocumentId.ofString(I_M_HU_Storage.Table_Name + "_HU" + huId + "_P" + product.getM_Product_ID()))
				.setType(HUDocumentViewType.HUStorage)
				.setProcessed(processed)
				//
				.setHUId(huId)
				//.setCode(hu.getValue()) // NOTE: don't show value on storage level
				.setHUUnitType(huUnitTypeLookupValue)
				.setHUStatus(huStatus)
				//
				.setProduct(createProductLookupValue(product))
				.setUOM(createUOMLookupValue(huStorage.getC_UOM()))
				.setQtyCU(huStorage.getQty());

		if (huId != parent_HU_ID)
		{
			storageDocumentBuilder.setAttributesProvider(getAttributesProvider());
		}

		return storageDocumentBuilder.build();
	}

	private static JSONLookupValue createHUStatusLookupValue(final I_M_HU hu)
	{
		final String huStatusKey = hu.getHUStatus();
		final String huStatusDisplayName = Services.get(IADReferenceDAO.class).retriveListName(Env.getCtx(), IHUDocumentView.HUSTATUS_AD_Reference_ID, huStatusKey);
		return JSONLookupValue.of(huStatusKey, huStatusDisplayName);
	}

	private static JSONLookupValue createProductLookupValue(final I_M_Product product)
	{
		if (product == null)
		{
			return null;
		}

		final String displayName = product.getValue() + "_" + product.getName();
		return JSONLookupValue.of(product.getM_Product_ID(), displayName);
	}

	private static JSONLookupValue createUOMLookupValue(final I_C_UOM uom)
	{
		if (uom == null)
		{
			return null;
		}

		return JSONLookupValue.of(uom.getC_UOM_ID(), uom.getUOMSymbol());
	}

}