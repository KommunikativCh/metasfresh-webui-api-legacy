package de.metas.ui.web.window.model;

import java.util.Comparator;
import java.util.List;

import com.google.common.collect.ImmutableList;

import de.metas.ui.web.view.IViewRow;
import de.metas.ui.web.window.datatypes.json.JSONOptions;
import de.metas.ui.web.window.model.DocumentQueryOrderBy.FieldValueExtractor;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

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

@UtilityClass
public class DocumentQueryOrderBys
{
	public static List<DocumentQueryOrderBy> emptyList()
	{
		return ImmutableList.of();
	}

	public static <T extends IViewRow> Comparator<T> asComparator(
			@NonNull final List<DocumentQueryOrderBy> orderBys,
			@NonNull final JSONOptions jsonOpts)
	{
		final FieldValueExtractor<T> fieldValueExtractor = IViewRow::getFieldValueAsJsonObject;

		// used in case orderBys is empty or whatever else goes wrong
		final Comparator<T> noopComparator = (o1, o2) -> 0;

		return orderBys.stream()
				.map(orderBy -> orderBy.<T> asComparator(fieldValueExtractor, jsonOpts))
				.reduce(Comparator::thenComparing)
				.orElse(noopComparator);
	}
}
