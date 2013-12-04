/*
 * Copyright 2013, Augmented Technologies Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.augtech.geoapi.geopackage.table;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.feature.type.Name;
import org.opengis.geometry.BoundingBox;

import com.augtech.geoapi.geopackage.DateUtil;
import com.augtech.geoapi.geopackage.GeoPackage;
import com.augtech.geoapi.geopackage.GpkgField;
import com.augtech.geoapi.geopackage.GpkgRecords;
import com.augtech.geoapi.geopackage.GpkgTable;
import com.augtech.geoapi.geopackage.ICursor;
import com.augtech.geoapi.geopackage.table.GpkgDataColumnConstraint.DataColumnConstraint;

/** An extension to the standard {@link GpkgTable} that provides specific functionality
 * relating to a vector feature table within the GeoPackage as well as an enclosed
 * class for {@link GeometryInfo} that relates to this table.
 * 
 *
 */
public class FeatureTable extends GpkgTable {
	
	/** The feature ID to use in the database and on this table */
	String featureFieldID = GeoPackage.FEATURE_ID_FIELD_NAME;
	GeoPackage geoPackage = null;
	GeometryInfo geometryInfo = null;
	
	/** Create a new FeaturesTable. The tables feature id field (if required)
	 * will be set to 'feature_id'.
	 * 
	 * @param geoPackage The GeoPackage this table relates to
	 * @param tableName The name of the table. Any spaces will be replaced by '_'
	 * @see {@link #create(SimpleFeatureType, BoundingBox, GeometryDescriptor)}
	 * @see {@link #getBounds()} or {@link #getFields()} or {@link #getGeometryInfo()} to populate from the
	 * GeoPackage
	 */
	public FeatureTable(GeoPackage geoPackage, String tableName) {
		this(geoPackage, tableName, GeoPackage.FEATURE_ID_FIELD_NAME);
	}
	/** Create a new FeaturesTable.<p>
	 * Note that the table will neither be created, or populated from, the GeoPackage
	 * until one of the relevant methods are called.
	 * 
	 * @param geoPackage The GeoPackage this table relates to
	 * @param tableName The name of the table. Any spaces will be replaced by '_'
	 * @param featureFieldID The name of the field to use as a featureID when constructing
	 * {@link SimpleFeature}'s
	 * @see {@link #create(SimpleFeatureType, BoundingBox, GeometryDescriptor)}
	 * @see {@link #getBounds()} or {@link #getFields()} or {@link #getGeometryInfo()} to populate from the
	 * GeoPackage
	 */
	public FeatureTable(GeoPackage geoPackage, String tableName, String featureFieldID) {
		super(tableName.replace(" ", "_"), null, null);
		super.tableType = GpkgTable.TABLE_TYPE_FEATURES;
		this.geoPackage = geoPackage;
		this.featureFieldID = featureFieldID;
	}


	/** Create a 'Features' table to hold vector features and insert the table details into 
	 * gpkg_contents. The SRS is taken from the {@link BoundingBox}.<p>
	 * The geometry that the user inserts for each feature must match the supplied SRS.<p>
	 * The last last_change field will be set to the time of creation.<p>
	 * Attribute information for into the gpkg_data_columns table
	 * 'full_name', 'mime_type' and 'constraint' are taken from {@link AttributeType#getUserData()} 
	 * to populate their respective fields in the gpkg_data_columns table. Remaining values are taken from
	 * {@link AttributeType#getName()} and {@link AttributeType#getDescription()}. Note that constraints 
	 * must be added to the database via {@link #addDataColumnConstraint(DataColumnConstraint)} prior
	 * to specifying and passing them through this method.<p>
	 * Table field data-types are determined from {@link AttributeType#getBinding()}
	 * 
	 * @param featureType The {@link SimpleFeatureType} that defines the table and its contents<br>
	 * The name of the table is taken from the local part of {@link SimpleFeatureType#getName()} with 
	 * spaces replaced by '_'.<br>
	 * Feature table description is taken from a user-data value name of 'Description'.
	 * @param bbox The {@link BoundingBox} ) provides an informative bounding box 
	 * (not necessarily minimum bounding box) of the content.
	 * 
	 * @return Returns {@code True} if the table(s) are created or the table already exists in
	 * the GeoPackage. False if any of the inserts fail.
	 * @throws Exception If the supplied data is invalid or constraints are not met (i.e No matching SRS 
	 * definition in the gpkg_spatial_ref_sys table)
	 */
	public boolean create(SimpleFeatureType featureType, BoundingBox bbox) throws Exception {
		
		GeometryDescriptor geomDescriptor = featureType.getGeometryDescriptor();
		
		if (!geoPackage.isGeomTypeValid(geomDescriptor) )
			throw new Exception("Invalid geometry type for table");
		
		if (isTableInGpkg(geoPackage)) {
			geoPackage.log.log(Level.WARNING, "Table "+tableName+" already defined in "+GpkgContents.TABLE_NAME);
			return true;
		}

		// Doesn't exist in Contents, but does in DB, therefore not valid and drop
		if (isTableInDB(geoPackage)) {
			geoPackage.log.log(Level.INFO, "Replacing table "+tableName);
			geoPackage.getDatabase().execSQL("DROP table "+tableName);
		}
		
		String raw = null;
		
		// Check SRS exists in gpkg_spatial_ref_sys table
		int srsID = Integer.parseInt( geomDescriptor.getCoordinateReferenceSystem().getName().getCode() );

		GpkgRecords records = geoPackage.getSystemTable(GpkgSpatialRefSys.TABLE_NAME)
								.query(geoPackage, "srs_id="+srsID);

		if (records.getFieldInt(0, "srs_id")!=srsID) 
			throw new Exception("SRS "+srsID+" does not exist in the gpkg_spatial_ref_sys table");

		/* Checks passed, build queries for insertion...*/
		
		/* TODO Replace all field definitions with something more generic, or 
		 * constants on class... */
		
		// Construct 'fields' text
		String geomName = geomDescriptor.getLocalName();
		List<String> dataColumnDefs = new ArrayList<String>();

		/* Always add a feature_id column to table and gpkg_data_columns
		 * to enable WFS (and similar) IDs to be re-created when reading back 
		 * out of the table */
		String fields = ", "+featureFieldID+" TEXT";
		dataColumnDefs.add(
				"INSERT INTO gpkg_data_columns (table_name, column_name, name, title) "+
				" VALUES ('"+tableName+"','"+featureFieldID+"','FeatureID', 'FeatureID');");
		
		/* We always add full descriptions into GpkgDataColumns for each
		 * attribute even though its optional */
		for (int i=0;i<featureType.getAttributeCount();i++) {
			
			AttributeType aType = featureType.getType(i);
			Name atName = aType.getName();
			
			// Don't add Geometry to table def, but do add it into gpkg_data_columns
			if (atName.equals(geomDescriptor.getName())) {
				dataColumnDefs.add(String.format(
						"INSERT INTO gpkg_data_columns (table_name, column_name, name, title) "+
						" VALUES ('%s','%s','%s','Feature Geometry')",
						tableName,
						geomName,
						geomName
						) );
				
				continue;
			}
			
			// The insertion text
			fields+=", "+atName.getLocalPart() + " " + geoPackage.encodeType( aType.getBinding() );
			
			// Data columns definitions...
			//table_name, column_name, name, title, description, mime_type, constraint_name
			String mime = (String) aType.getUserData().get("mime_type");
			String constraint = (String) aType.getUserData().get("constraint_name");

			dataColumnDefs.add(String.format(
					"INSERT INTO gpkg_data_columns (table_name, column_name, name, title, description, mime_type, constraint_name) "+
					" VALUES ('%s','%s','%s','%s','%s',%s,%s)",
					tableName,
					aType.getName().getLocalPart(),
					aType.getName().toString(),
					aType.getUserData().get("full_name"),
					aType.getDescription().toString(),
					mime!=null ? "'"+mime+"'" : null,
					constraint!=null ? "'"+constraint+"'" : null
					) );
		}
		
		if (geomName.equals(""))
			throw new Exception("Unable to decode geometry attribute.");
		
		Object description = featureType.getUserData().get("Description");
		description = description==null ? "" : description.toString();
		
		// Create table
		String tableDef = "CREATE TABLE "+tableName+" ( "+
			"id INTEGER PRIMARY KEY AUTOINCREMENT, "+
			geomDescriptor.getLocalName() + " GEOMETRY"+
			fields +");";

		// Geometry columns
		raw = 	"INSERT INTO gpkg_geometry_columns (table_name, column_name, geometry_type_name, srs_id, z, m) "+
				" VALUES ('%s','%s','%s',%s,%s,%s);";
		String geomDef = String.format(raw, 
				tableName, 
				geomDescriptor.getLocalName(), 
				geomDescriptor.getType().getName().getLocalPart().toLowerCase(), 
				srsID, 
				GeoPackage.Z_M_VALUES_OPTIONAL, 
				GeoPackage.Z_M_VALUES_OPTIONAL);
		
		// If checks past, insert definition to gpkg_contents
		raw = "INSERT INTO gpkg_contents (table_name,data_type,identifier,description,last_change,"+
		"min_x,min_y,max_x,max_y,srs_id) VALUES ('%s','%s','%s','%s','%s',%s,%s,%s,%s,%s);";
		String contentsDef = String.format(raw, 
				tableName,
				GpkgTable.TABLE_TYPE_FEATURES,
				tableName,
				description,
				DateUtil.serializeDateTime(System.currentTimeMillis(), true),
				bbox.getMinX(),
				bbox.getMinY(),
				bbox.getMaxX(),
				bbox.getMaxY(),
				srsID );

		
		// Execute the commands as a single transaction to allow for rollback...
		String[] statements = new String[dataColumnDefs.size()+3];
		statements[0] = tableDef;
		statements[1] = geomDef;
		statements[2] = contentsDef;
		for (int s=3; s<dataColumnDefs.size()+3; s++) {
			statements[s] = dataColumnDefs.get(s-3);
		}

		boolean success = geoPackage.getDatabase().execSQLWithRollback(statements);
		
		// Get the information back from DB
		getContents();
		
		return success;
	}

	/* (non-Javadoc)
	 * @see com.augtech.geoapi.geopackage.table.GpkgTable#query(com.augtech.geoapi.geopackage.GeoPackage, java.lang.String)
	 */
	@Override
	public GpkgRecords query(GeoPackage geoPackage, String strWhere) throws Exception {
		if (super.getFields().size()==0) {
			getContents();
		}
		return super.query(geoPackage, strWhere);
	}


	/* (non-Javadoc)
	 * @see com.augtech.geoapi.geopackage.table.GpkgTable#getField(java.lang.String)
	 */
	@Override
	public GpkgField getField(String fieldName) {
		if (super.getFields().size()==0) {
			try {
				getContents();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return super.getField(fieldName);
	}
	/** Get extended information for this FeatureTable
	 * 
	 * @throws Exception
	 */
	private void getContents() throws Exception {
		// Probably already been built
		if (super.getFields().size()>0 && geometryInfo!=null) return;
		
		// Standard info from gpkg_contents and table definition
		super.getContents(geoPackage);
		
		/* Get extended information about each field for this table from GpkgDataColumns.
		 * There may or may not be column definitions in GpkgDataColumns */
		Map<String, FeatureField> dataColumns = new HashMap<String, FeatureField>();
		ICursor dcCursor = geoPackage.getSystemTable(GpkgDataColumns.TABLE_NAME).query(geoPackage, null, "table_name='"+tableName+"'");
		
		if (dcCursor!=null) {
			while (dcCursor.moveToNext()) {
				
				String fName = dcCursor.getString(dcCursor.getColumnIndex("column_name"));
				FeatureField fField = new FeatureField(fName, "TEXT");
				
				fField.featureID = fName.equals(GeoPackage.FEATURE_ID_FIELD_NAME);
				fField.name = dcCursor.getString(dcCursor.getColumnIndex("name"));
				fField.title = dcCursor.getString(dcCursor.getColumnIndex("title"));
				fField.description = dcCursor.getString(dcCursor.getColumnIndex("description"));
				fField.mimeType = dcCursor.getString(dcCursor.getColumnIndex("mime_type"));
				String conName =  dcCursor.getString(dcCursor.getColumnIndex("constraint_name"));
				fField.constraint = new GpkgDataColumnConstraint().getConstraint(geoPackage, conName);
				
				dataColumns.put(fName, fField);
			}
			
		}
		dcCursor.close();
		
		/* Go through the table fields ( from getContents() ) and update with the additional info
		 * from GpkgDataColumns */
		for (GpkgField gf : super.getFields()) {
			
			FeatureField fField = dataColumns.get( gf.getFieldName() );
			if (fField==null) continue;
			
			// Already defined, so copy updating the field data-type from the table_info
			fField = new FeatureField(fField, gf.getFieldType() );
			
			// Update the base field with the new extended one.
			super.addField(fField);
			
		}
		
		// Finally get the geometry info on to this table
		getGeometryInfo();
		
	}

	/** Get all geometry information for this table
	 * 
	 * @return
	 * @see #getBounds() for GpkgContents defined bounding box.
	 * @throws Exception
	 */
	public GeometryInfo getGeometryInfo() throws Exception {
		if (geometryInfo!=null) return geometryInfo;
		
		geometryInfo = new GeometryInfo();
		
		// Geometry column details
		GpkgRecords gRecord = geoPackage.getSystemTable(GpkgGeometryColumns.TABLE_NAME)
				.query(geoPackage, "table_name='"+tableName+"';");
		
		if (gRecord==null)
			throw new Exception("No geometry field definition for "+tableName);
		
		geometryInfo.columnName = gRecord.getField(0, "column_name");
		geometryInfo.geometryTypeName = gRecord.getField(0, "geometry_type_name");
		geometryInfo.srsID = gRecord.getFieldInt(0, "srs_id");
		int z = gRecord.getFieldInt(0, "z");
		if (z!=-1) geometryInfo.z = z;
		int m = gRecord.getFieldInt(0, "m");
		if (m!=-1) geometryInfo.m = m;
		
		// Check and get the SRID is defined in GeoPackage
		GpkgRecords sRecord = geoPackage.getSystemTable(GpkgSpatialRefSys.TABLE_NAME)
				.query(geoPackage, "srs_id="+geometryInfo.srsID);
		if (sRecord==null || sRecord.get(0)==null)
			throw new Exception("SRS "+geometryInfo.srsID+" not defined in GeoPackage");
		
		geometryInfo.organization = sRecord.getField(0, "organization");
		geometryInfo.definition = sRecord.getField(0, "definition");
		
		return geometryInfo;
	}
	/* (non-Javadoc)
	 * @see com.augtech.geoapi.geopackage.table.GpkgTable#getFields()
	 */
	@Override
	public Collection<GpkgField> getFields() {
		if (super.getFields().size()==0) {
			try {
				getContents();
			} catch (Exception e) {
				e.printStackTrace();
				return null;
			}
		}

		return super.getFields();
	}
	/**
	 * @return the BoundingBox from GpkgContents
	 */
	@Override
	public BoundingBox getBounds() {
		if (super.getBounds().isEmpty()) {
			try {
				super.getContents(geoPackage);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		return super.getBounds();
	}

	/**
	 * @return the lastChange
	 */
	@Override
	public Date getLastChange() {
		if (super.getLastChange()==null) {
			try {
				super.getContents(geoPackage);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return super.getLastChange();
	}
	
	/** A class for storing FeaturesTable geometry information
	 * 
	 *
	 */
	public class GeometryInfo {
		protected String columnName = "";
		protected String geometryTypeName = "";
		protected String organization = "EPSG";
		protected String definition = "";
		protected int srsID = -1;
		protected int z = GeoPackage.Z_M_VALUES_OPTIONAL;
		protected int m = GeoPackage.Z_M_VALUES_OPTIONAL;
		
		public GeometryInfo() {
		}

		/**
		 * @return the columnName
		 */
		public String getColumnName() {
			return columnName;
		}

		/**
		 * @return the geometryTypeName
		 */
		public String getGeometryTypeName() {
			return geometryTypeName;
		}

		/**
		 * @return the organization
		 */
		public String getOrganization() {
			return organization;
		}

		/**
		 * @return the definition
		 */
		public String getDefinition() {
			return definition;
		}

		/**
		 * @return the srsID
		 */
		public int getSrsID() {
			return srsID;
		}

		/**
		 * @return the z
		 */
		public int getZ() {
			return z;
		}

		/**
		 * @return the m
		 */
		public int getM() {
			return m;
		}
		
		
	}
	
	
	//public static final String CREATE_TABLE_GPKG_DATA_COLUMN_CONSTRAINTS = "CREATE TABLE gpkg_data_column_constraints ( "+
//	"constraint_name TEXT NOT NULL, "+
//	"constraint_type TEXT NOT NULL, "+ /* 'range' | 'enum' | 'glob' */
//	"value TEXT, "+
//	"min NUMERIC, "+
//	"minIsInclusive BOOLEAN, "+ /* 0 = false, 1 = true */
//	"max NUMERIC, "+
//	"maxIsInclusive BOOLEAN, "+  /* 0 = false, 1 = true */
//	"Description TEXT, "+
//	"CONSTRAINT gdcc_ntv UNIQUE (constraint_name, constraint_type, value) )";
	
}