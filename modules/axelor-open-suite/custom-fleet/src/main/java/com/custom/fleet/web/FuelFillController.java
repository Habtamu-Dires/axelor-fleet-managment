package com.custom.fleet.web;

import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.custom.fleet.db.FuelFill;

public class FuelFillController {

    public void updateProductDomain(ActionRequest request, ActionResponse response) {
        // get the current state of the form
        FuelFill fuelFill = request.getContext().asType(FuelFill.class);

        // if the user clear the location clear the product domain
        if(fuelFill.getStockLocation() == null){
            response.setAttr("product","domain", "1 = 0");
            return ;
        }

        //3. Define your JPQL domain (Notice we use :stockLocation to reference the form field)
        String jpqlDomain = "self.id IN (" +
                "  SELECT sll.product.id " +
                "  FROM com.axelor.apps.stock.db.StockLocationLine sll " +
                "  WHERE sll.stockLocation = :stockLocation " +
                "  AND sll.currentQty > 0" +
                ")";

        // 4. Send the domain back to the UI, applying it to the "product" field
        response.setAttr("product", "domain", jpqlDomain);

    }
}
