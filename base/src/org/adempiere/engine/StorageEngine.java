/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * Copyright (C) 2003-2007 e-Evolution,SC. All Rights Reserved.               *
 * Contributor(s): victor.perez@e-evolution.com http://www.e-evolution.com    *
 *                 Teo Sarca, www.arhipac.ro                                  *
 *****************************************************************************/

package org.adempiere.engine;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Properties;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MAttributeSetInstance;
import org.compiere.model.MClient;
import org.compiere.model.MInOut;
import org.compiere.model.MInOutLine;
import org.compiere.model.MInOutLineMA;
import org.compiere.model.MLocator;
import org.compiere.model.MMPolicyTicket;
import org.compiere.model.MProduct;
import org.compiere.model.MStorage;
import org.compiere.model.MTable;
import org.compiere.model.MTransaction;
import org.compiere.model.MWarehouse;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 * Storage Engine
 * @author victor.perez@e-evolution.com http://www.e-evolution.com
 * @author Teo Sarca
 */
public class StorageEngine 
{
	
	/**	Logger							*/
	protected static transient CLogger	log = CLogger.getCLogger (StorageEngine.class);
	
	public static void createTrasaction (
			IDocumentLine docLine,
			String MovementType , 
			Timestamp MovementDate , 
			BigDecimal Qty, 
			boolean isReversal , 
			int M_Warehouse_ID, 
			int o_M_AttributeSetInstance_ID,
			int o_M_Warehouse_ID,
			boolean isSOTrx
		)
	{	
		//	Incoming Trx
		boolean incomingTrx = MovementType.charAt(1) == '+';	//	V+ Vendor Receipt
		boolean sameWarehouse = M_Warehouse_ID == o_M_Warehouse_ID;

		MProduct product = MProduct.get(docLine.getCtx(), docLine.getM_Product_ID());
		if (product == null || !product.isStocked())
			return;
			
		//Ignore the Material Policy when is Reverse Correction
		if(!isReversal)
		{
			checkMaterialPolicy(docLine, MovementType, MovementDate, M_Warehouse_ID);
		}
		
		// Reservation ASI
		int reservationAttributeSetInstance_ID = o_M_AttributeSetInstance_ID;
		//
		if (!incomingTrx)  // Same as (docLine.getM_MPolicyTicket_ID() == 0)
		{
			// Outgoing transactions are allocated to tickets using the line material allocation
			// table.  Update the storage data accordingly.
			IInventoryAllocation mas[] = StorageEngine.getMA(docLine);
			for (int j = 0; j < mas.length; j++)
			{
				IInventoryAllocation ma = mas[j];
				BigDecimal QtyMA = ma.getMovementQty();
//				if (!incomingTrx)	//	C- Customer Shipment - V- Vendor Return
				QtyMA = QtyMA.negate();		
				
				BigDecimal reservedDiff = Env.ZERO;
				BigDecimal orderedDiff = Env.ZERO;

				if (docLine instanceof MInOutLine && ((MInOutLine) docLine).getC_OrderLine_ID() != 0)
				{			
					if ((isSOTrx && MInOut.MOVEMENTTYPE_CustomerShipment.equals(MovementType) && ma.getMovementQty().signum() > 0) // Shipment
					||	(isSOTrx &&  MInOut.MOVEMENTTYPE_CustomerReturns.equals(MovementType) &&  ma.getMovementQty().signum() < 0)) // Revert Customer Return
						reservedDiff =  ma.getMovementQty().abs().negate();
					else if ((isSOTrx && MInOut.MOVEMENTTYPE_CustomerShipment.equals(MovementType) && ma.getMovementQty().signum() < 0) // Revert Shipment
					|| (isSOTrx && MInOut.MOVEMENTTYPE_CustomerReturns.equals(MovementType) && ma.getMovementQty().signum() > 0)) // Customer Return
						reservedDiff = ma.getMovementQty().abs();
					else if ((!isSOTrx &&  MInOut.MOVEMENTTYPE_VendorReceipts.equals(MovementType) && ma.getMovementQty().signum() > 0) // Vendor Receipt
					|| (!isSOTrx &&  MInOut.MOVEMENTTYPE_VendorReturns.equals(MovementType) && ma.getMovementQty().signum() < 0)) // Revert Return Vendor
						orderedDiff = ma.getMovementQty().abs().negate();
					else if ((!isSOTrx &&  MInOut.MOVEMENTTYPE_VendorReceipts.equals(MovementType) && ma.getMovementQty().signum() < 0)  // Revert Vendor Receipt
					|| (!isSOTrx &&  MInOut.MOVEMENTTYPE_VendorReturns.equals(MovementType) && ma.getMovementQty().signum() > 0))  // Return Vendor 
						orderedDiff = ma.getMovementQty().abs();
				}

				
				//	Update Storage - see also VMatch.createMatchRecord
				if (!MStorage.add(docLine.getCtx(), M_Warehouse_ID,
					docLine.getM_Locator_ID(),
					docLine.getM_Product_ID(), 
					docLine.getM_AttributeSetInstance_ID(), reservationAttributeSetInstance_ID,
					ma.getM_MPolicyTicket_ID(),
					QtyMA,
					sameWarehouse ? reservedDiff : Env.ZERO,
					sameWarehouse ? orderedDiff : Env.ZERO,
					docLine.get_TrxName()))
				{
					throw new AdempiereException(); //Cannot correct Inventory (MA)
				}
				if (!sameWarehouse) {
					//correct qtyOrdered/qtyReserved in warehouse of order
					MWarehouse wh = MWarehouse.get(docLine.getCtx(), o_M_Warehouse_ID);
					if (!MStorage.add(docLine.getCtx(), o_M_Warehouse_ID,
							wh.getDefaultLocator().getM_Locator_ID(),
							docLine.getM_Product_ID(), 
							docLine.getM_AttributeSetInstance_ID(), reservationAttributeSetInstance_ID,
							ma.getM_MPolicyTicket_ID(),
							Env.ZERO,
							reservedDiff,
							orderedDiff,
							docLine.get_TrxName()))
						{
							throw new AdempiereException(); //Cannot correct Inventory (MA)
						}
			
				}
				create(docLine, MovementType ,MovementDate, ma.getM_MPolicyTicket_ID() , QtyMA);
			}
		}
		//	sLine.getM_AttributeSetInstance_ID() != 0
		//if (mtrx == null)
		else // incomingTrx
		{
//			if (!incomingTrx)	//	C- Customer Shipment - V- Vendor Return
//				Qty = Qty.negate();
							
			if (docLine.getM_MPolicyTicket_ID() == 0)
				throw new AdempiereException ("@Error@ @FillMandatory@ @M_MPolicyTicket_ID@");

			BigDecimal reservedDiff = Env.ZERO;
			BigDecimal orderedDiff = Env.ZERO;

			if (docLine instanceof MInOutLine && ((MInOutLine) docLine).getC_OrderLine_ID() != 0)
			{			
				if ((isSOTrx && MInOut.MOVEMENTTYPE_CustomerShipment.equals(MovementType) && Qty.signum() > 0) // Shipment
				||	(isSOTrx &&  MInOut.MOVEMENTTYPE_CustomerReturns.equals(MovementType) &&  Qty.signum() < 0)) // Revert Customer Return
					reservedDiff =  Qty.abs().negate();
				else if ((isSOTrx && MInOut.MOVEMENTTYPE_CustomerShipment.equals(MovementType) && Qty.signum() < 0) // Revert Shipment
				|| (isSOTrx && MInOut.MOVEMENTTYPE_CustomerReturns.equals(MovementType) && Qty.signum() > 0)) // Customer Return
					reservedDiff = Qty.abs();
				else if ((!isSOTrx &&  MInOut.MOVEMENTTYPE_VendorReceipts.equals(MovementType) && Qty.signum() > 0) // Vendor Receipt
				|| (!isSOTrx &&  MInOut.MOVEMENTTYPE_VendorReturns.equals(MovementType) && Qty.signum() < 0)) // Revert Return Vendor
					orderedDiff = Qty.abs().negate();
				else if ((!isSOTrx &&  MInOut.MOVEMENTTYPE_VendorReceipts.equals(MovementType) && Qty.signum() < 0)  // Revert Vendor Receipt
				|| (!isSOTrx &&  MInOut.MOVEMENTTYPE_VendorReturns.equals(MovementType) && Qty.signum() > 0))  // Return Vendor 
					orderedDiff = Qty.abs();
			}

			// Update storage
			if (!MStorage.add(docLine.getCtx(), M_Warehouse_ID,
					docLine.getM_Locator_ID(),
					docLine.getM_Product_ID(), 
					docLine.getM_AttributeSetInstance_ID(), reservationAttributeSetInstance_ID,
					docLine.getM_MPolicyTicket_ID(),
					Qty,
					sameWarehouse ? reservedDiff : Env.ZERO,
					sameWarehouse ? orderedDiff : Env.ZERO,
					docLine.get_TrxName())) 
				{
					throw new AdempiereException(); //Cannot correct Inventory (MA)
				}
			if (!sameWarehouse  && o_M_Warehouse_ID>0 && docLine instanceof MInOutLine) {
				//correct qtyOrdered/qtyReserved in warehouse of order
				MWarehouse wh = MWarehouse.get(docLine.getCtx(), o_M_Warehouse_ID);
				if (!MStorage.add(docLine.getCtx(), o_M_Warehouse_ID,
						wh.getDefaultLocator().getM_Locator_ID(),
						docLine.getM_Product_ID(), 
						docLine.getM_AttributeSetInstance_ID(), reservationAttributeSetInstance_ID,
						docLine.getM_MPolicyTicket_ID(),
						Env.ZERO,
						reservedDiff,
						orderedDiff,
						docLine.get_TrxName()))
					{
						throw new AdempiereException(); //Cannot correct Inventory (MA)
					}
		
			}
			create(docLine, MovementType ,MovementDate, docLine.getM_MPolicyTicket_ID() , Qty);
		}
	}


	/**
	 * 	Check Material Policy<br>
	 *  This function ensures that material transactions follow the material 
	 *  policy of fifo/lifo.  Each document line with an incoming product is 
	 *  given a material policy ticket and this ticket is included in the 
	 *  storage of the product.  For outgoing products, the material policy 
	 *  is used to select the tickets that will be used to fulfill the 
	 *  transaction. Tickets will be added to the document line Material 
	 *  Allocation (for example MInOutLineMA).<br>
	 *  <br>
	 *  If a transaction forces the quantity on hand to be negative, an error 
	 *  will be returned.  The source document should not be completed until 
	 *  there is sufficient quantity on hand.<br>  
	 *  <br>
	 *  Negative quantity-on-hand can exists, created by reversals of material 
	 *  receipts for example.  The system assumes that the next material receipt 
	 *  processed with the same product/asi will be a correction and will use the 
	 *  same ticket as the negative quantity.<br>
	 *  <br>
	 *  The function also sets the locator to the default for the warehouse if no
	 *  locator is defined on the line and there is insufficient stock of that product
	 *  and Attribute Set Instance.  If stock exists in the warehouse, the locator
	 *  with the highest priority and sufficient stock on hand is used.  For incoming
	 *  material, the default locator is used.  
	 *     
	 *  @param line - the document line that contains the product and ASI (if any)
	 *  @param MovementType - a string that follows the movement type patterns (For example "C-" or "V+")
	 *  @param MovementDate - a timestamp with the date the movement occurred
	 *  @param M_Warehouse_ID - the ID of the Warehouse to use
	 *  
	 *  @since 3.9.0 - prior to 3.9.0, the material attribute set instances were 
	 *  used as tickets. See <a href="https://github.com/adempiere/adempiere/issues/453">BR 453 
	 *  Attribute Set Instances are used to track FIFO/LIFO. Another method is 
	 *  required.</a>
	 *  
	 *  @see org.compiere.model.MMPolicyTicket
	 */
	private static void checkMaterialPolicy(
			IDocumentLine line, 
			String MovementType, 
			Timestamp MovementDate, 
			int M_Warehouse_ID)
	{
		// In case the document process is being redone, delete work in progress
		// and start again.
		deleteMA(line);

		//	Incoming Trx
		boolean incomingTrx = MovementType.charAt(1) == '+';	//	V+ Vendor Receipt
		MProduct product = MProduct.get(line.getCtx(), line.getM_Product_ID());
		if (product == null)  // Nothing to ticket
			return;

		//	Need to have Location
		if (line.getM_Locator_ID() == 0)
		{
			//MWarehouse w = MWarehouse.get(getCtx(), getM_Warehouse_ID());
			//line.setM_Warehouse_ID(M_Warehouse_ID);
			//line.setM_Locator_ID(getM_Locator_ID(line.getCtx(),line.getM_Warehouse_ID(), line.getM_Product_ID(),line.getM_AttributeSetInstance_ID(), incomingTrx ? Env.ZERO : line.getMovementQty(), line.get_TrxName()));
		}
	
		//	Material Policy Tickets - used to track the FIFO/LIFO
		//  Create a Material Policy Ticket ID for any incoming transaction
		//  Where there is negative material on-hand, receive the new material using the ticket
		//  of the negative material.  This assumes the material receipt is a correction of the 
		//  cause of the negative quantity.  A single ticket is used as the costs are unique for 
		//  each receipt line 
		if (incomingTrx)
		{
			MMPolicyTicket ticket = null;
			//  Find the storage locations to use.  Prioritize any negative quantity-on-hand and 
			//  apply this material receipt to the associated ticket
			MStorage[] storages = MStorage.getWarehouse(line.getCtx(), M_Warehouse_ID, line.getM_Product_ID(), 0, 0,
					null, MClient.MMPOLICY_FiFo.equals(product.getMMPolicy()), false, line.getM_Locator_ID(), line.get_TrxName());
			for (MStorage storage : storages) 
			{
				if (storage.getQtyOnHand().signum() < 0) 
				{
					// Negative quantity - use that ticket
					ticket = new MMPolicyTicket(line.getCtx(),storage.getM_MPolicyTicket_ID(), line.get_TrxName());
					break;
				}
			}
			//  Always create a material policy ticket so fifo/lifo work.
			if (ticket == null)
			{
				ticket = MMPolicyTicket.create(line.getCtx(), line, MovementDate, line.get_TrxName());
				if (ticket == null) { // There is a problem
					log.severe("Can't create Material Policy Ticket for line " + line);
					throw new AdempiereException("Can't create Material Policy Ticket for line " + line);
				}
			}
			//  For incoming transactions, one ticket is created per MR line.			
			line.setM_MPolicyTicket_ID(ticket.getM_MPolicyTicket_ID());
			log.config("New Material Policy Ticket=" + line);
			//createMA(line, ticket.getM_MPolicyTicket_ID(), line.getMovementQty()); // Why?
		} // Incoming			
		else // Outgoing - use Material Allocation
		{
			String MMPolicy = product.getMMPolicy();
			Timestamp minGuaranteeDate = MovementDate;
			MStorage[] storages = MStorage.getWarehouse(line.getCtx(), M_Warehouse_ID, 
					line.getM_Product_ID(), line.getM_AttributeSetInstance_ID(), 0, 
					minGuaranteeDate, MClient.MMPOLICY_FiFo.equals(MMPolicy), true, line.getM_Locator_ID(), line.get_TrxName());
			BigDecimal qtyToDeliver = line.getMovementQty();
			for (MStorage storage : storages)
			{						
				if (storage.getQtyOnHand().compareTo(qtyToDeliver) >= 0)
				{
					createMA (line, storage.getM_MPolicyTicket_ID(), qtyToDeliver);
					qtyToDeliver = Env.ZERO;
				}
				else
				{	
					createMA (line, storage.getM_MPolicyTicket_ID(), storage.getQtyOnHand());
					qtyToDeliver = qtyToDeliver.subtract(storage.getQtyOnHand());
					log.fine("QtyToDeliver=" + qtyToDeliver);						
				}

				if (qtyToDeliver.signum() == 0)
					break;
			}

			if (qtyToDeliver.signum() != 0)
			{
				//deliver using new asi
				//MAttributeSetInstance asi = MAttributeSetInstance.create(line.getCtx(), product, line.get_TrxName());
				//createMA(line, asi.getM_AttributeSetInstance_ID(), qtyToDeliver);
				
				// There is not enough stock to deliver this shipment. Flag this 
				// as an error.  Remove any Material Allocations already created.
				// TODO - this should trigger a way to balance costs - outgoing shipments 
				// could have accounting with a generic cost guess (Steve's Shipment Plan for example).
				// The balancing incoming transaction could have accounting to reverse the generic 
				// cost and add the correct one.
				log.warning(line + ", Insufficient quantity. Process later.");
				deleteMA(line);
				throw new AdempiereException("Insufficient quantity to deliver line " + line);
			}
		}	//	outgoing Trx
		save(line);
	}
	
	private static String getTableNameMA(IDocumentLine model)
	{
		return model.get_TableName()+"MA";
	}
	
	private static int deleteMA(IDocumentLine model)
	{
		String sql = "DELETE FROM "+getTableNameMA(model)+" WHERE "+model.get_TableName()+"_ID=?";
		int no = DB.executeUpdateEx(sql, new Object[]{model.get_ID()}, model.get_TrxName());
		if (no > 0)
			log.config("Delete old #" + no);
		return no;
	}
	
	private static void saveMA(IInventoryAllocation ma)
	{
		((PO)ma).saveEx();
	}
	
	private static void save(IDocumentLine line)
	{
		((PO)line).saveEx();
	}
	
	private static void create(IDocumentLine model, String MovementType, Timestamp MovementDate,
								int M_MPolicyTicket_ID, BigDecimal Qty)
	{
		MTransaction mtrx = new MTransaction (model.getCtx(), model.getAD_Org_ID(),
				MovementType, model.getM_Locator_ID(),
				model.getM_Product_ID(), model.getM_AttributeSetInstance_ID(), M_MPolicyTicket_ID,
				Qty, MovementDate, model.get_TrxName());
		setReferenceLine_ID(mtrx, model);
		mtrx.saveEx();
	}
	
	private static IInventoryAllocation createMA(IDocumentLine model, int M_MPolicyTicket_ID, BigDecimal MovementQty)
	{
		final Properties ctx = model.getCtx();
		final String tableName = getTableNameMA(model);
		final String trxName = model.get_TrxName();
		
		IInventoryAllocation ma = (IInventoryAllocation)MTable.get(ctx, tableName).getPO(0, trxName);
		ma.setAD_Org_ID(model.getAD_Org_ID());
		setReferenceLine_ID((PO)ma, model);
		ma.setM_MPolicyTicket_ID(M_MPolicyTicket_ID);
		ma.setMovementQty(MovementQty);
		
		saveMA(ma);
		log.fine("##: " + ma);
		
		return ma;
	}
	
	private static IInventoryAllocation[] getMA(IDocumentLine model)
	{
		final Properties ctx = model.getCtx();
		final String IDColumnName = model.get_TableName()+"_ID";
		final String tableName = getTableNameMA(model);
		final String trxName = model.get_TrxName();
		
		final String whereClause = IDColumnName+"=?";
		List<PO> list = new Query(ctx, tableName, whereClause, trxName)
											.setClient_ID()
											.setParameters(new Object[]{model.get_ID()})
											.setOrderBy(IDColumnName)
											.list();
		IInventoryAllocation[] arr = new IInventoryAllocation[list.size()];
		return list.toArray(arr);
	}
	
	private static void setReferenceLine_ID(PO model, IDocumentLine ref)
	{
		String refColumnName = ref.get_TableName()+"_ID";
		if (model.get_ColumnIndex(refColumnName) < 0)
		{
			throw new AdempiereException("Invalid inventory document line "+ref);
		}
		model.set_ValueOfColumn(refColumnName, ref.get_ID());
		
	}
		
	/**
	 * 	Set (default) Locator based on qty.
	 * 	@param Qty quantity
	 * 	Assumes Warehouse is set
	 */
	public static int getM_Locator_ID(
			Properties ctx,
			int M_Warehouse_ID, 
			int M_Product_ID, int M_AttributeSetInstance_ID,  
			BigDecimal Qty,
			String trxName)
	{	
		//	Get existing Location
		int M_Locator_ID = MStorage.getM_Locator_ID (M_Warehouse_ID, 
				M_Product_ID, M_AttributeSetInstance_ID, 0,
				Qty, trxName);
		//	Get default Location
		if (M_Locator_ID == 0)
		{
			MWarehouse wh = MWarehouse.get(ctx, M_Warehouse_ID);
			M_Locator_ID = wh.getDefaultLocator().getM_Locator_ID();
		}
		return M_Locator_ID;
	}	//	setM_Locator_ID

	public static void reserveOrOrderStock(Properties ctx, int M_Warehouse_ID, 
			int M_Product_ID, int M_AttributeSetInstance_ID, 
			BigDecimal qtyOrdered, BigDecimal qtyReserved, String trxName) {
		int M_Locator_ID = 0; 
		int M_MPolicyTicket_ID = 0; // Always for orders or reservations
		
		if (M_Product_ID <= 0)
			throw new AdempiereException("@Error@ @M_Product_ID@ @NotZero@");  //TODO check the translations

		if (M_Warehouse_ID <= 0)
			throw new AdempiereException("@Error@ @M_Warehouse_ID@ @NotZero@");  //TODO check the translations

		if (qtyOrdered.compareTo(Env.ZERO) == 0 && qtyReserved.compareTo(Env.ZERO) == 0)
			return; // Nothing to do
		
		if (qtyOrdered.compareTo(Env.ZERO) != 0 && qtyReserved.compareTo(Env.ZERO) != 0)
			throw new AdempiereException("@Error@ qtyOrdered and qtyReserved can't both be non-zero");  //TODO check the translations
		
		//	Get Locator to order/reserve
		// For orders, are there is a sufficient qty of this product/ASI (ASI could be zero) in inventory?
		// Get the locator with sufficient qty and with the highest locator priority.
		M_Locator_ID = MStorage.getM_Locator_ID (M_Warehouse_ID, 
				M_Product_ID, M_AttributeSetInstance_ID, M_MPolicyTicket_ID, 
			qtyOrdered, trxName);
		//	Get default Location
		if (M_Locator_ID == 0)
		{
			// try to take default locator for product first
			// if it is from the selected warehouse
			MProduct product = new MProduct(ctx, M_Product_ID, null);
			if (product == null)
				throw new AdempiereException ("@Error@ @M_Product-ID@=" + M_Product_ID + " can't be found.");
			
			MWarehouse wh = MWarehouse.get(ctx, M_Warehouse_ID);
			M_Locator_ID = product.getM_Locator_ID();
			if (M_Locator_ID!=0) {
				MLocator locator = new MLocator(ctx, product.getM_Locator_ID(), trxName);
				//product has default locator defined but is not from the order warehouse
				if(locator.getM_Warehouse_ID()!=wh.get_ID()) {
					M_Locator_ID = wh.getDefaultLocator().getM_Locator_ID();
				}
			} else {
				M_Locator_ID = wh.getDefaultLocator().getM_Locator_ID();
			}
		}
		//	Update Storage
		if (!MStorage.add(ctx, M_Warehouse_ID, M_Locator_ID, 
			M_Product_ID, 
			M_AttributeSetInstance_ID, M_AttributeSetInstance_ID, M_MPolicyTicket_ID,
			Env.ZERO, qtyReserved, qtyOrdered, trxName))
		{
			throw new AdempiereException(); //Cannot reserve or order stock
		}
			
	}
	
}
