package com.custom.fleet.web;

import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockLocationLine; // Corrected Entity
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.db.repo.StockLocationLineRepository; // Corrected Repository
import com.axelor.apps.stock.db.repo.StockLocationRepository;
import com.axelor.apps.stock.db.repo.StockMoveRepository;
import com.axelor.apps.stock.service.StockMoveService;
import com.axelor.db.JPA; // ADD THIS
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.custom.fleet.db.FuelFill;
import com.custom.fleet.db.Trip;
import com.custom.fleet.db.repo.FuelFillRepository;
import com.custom.fleet.db.repo.TripRepository;
import com.google.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class TripController {

  @Inject private TripRepository tripRepo;
  @Inject private FuelFillRepository fuelFillRepo;
  @Inject private StockMoveRepository stockMoveRepo;
  @Inject private StockLocationRepository stockLocationRepo;
  @Inject private StockMoveService stockMoveService;
  @Inject private StockLocationLineRepository stockLocationLineRepo; // Injected here

  public void processTripFuels(ActionRequest request, ActionResponse response) {
    Trip contextTrip = request.getContext().asType(Trip.class);

    if (contextTrip.getId() == null) {
      response.setError("Please save the Trip record first.");
      return;
    }

    try {
      // Explicitly open and manage an Axelor DB transaction block
      JPA.runInTransaction(
          () -> {
            Trip dbTrip = tripRepo.find(contextTrip.getId());
            int processedCount = 0;

            if (dbTrip.getFuelFillList() != null) {
              for (FuelFill fill : dbTrip.getFuelFillList()) {

                if (fill.getStockMove() == null) {
                  try {
                    createStockMoveForFuel(fill);
                    processedCount++;
                  } catch (IllegalArgumentException e) {
                    // 1. Unlink parent collection relationship reference
                    dbTrip.getFuelFillList().remove(fill);

                    // 2. Safely erase row within the active transaction
                    fuelFillRepo.remove(fill);

                    // 3. Save parent changes
                    tripRepo.save(dbTrip);

                    // 4. Queue the UI alert message
                    response.setAlert(
                        "Stock Validation Alert:\n\n"
                            + e.getMessage()
                            + "\n\nThe invalid fuel log entry has been removed from this trip.");

                    // Return safely from the transaction lambda block to trigger the commit
                    return;

                  } catch (Exception e) {
                    // Wrap checked exceptions to trigger a rollback on unexpected errors
                    throw new RuntimeException(e);
                  }
                }
              }
            }

            if (processedCount > 0) {
              response.setAlert(
                  "Successfully synchronized "
                      + processedCount
                      + " fuel consumption logs with stock ledger!");
            }
          });

    } catch (Exception e) {
      e.printStackTrace();
      response.setError("Error processing inventory movement: " + e.getMessage());
    }
  }

  private void createStockMoveForFuel(FuelFill fill) throws Exception {

    // --- Pre-Check Quantity Balance ---
    BigDecimal availableQty = BigDecimal.ZERO;

    // Query the correct Axelor 8.5 ledger for this product in the source location
    List<StockLocationLine> stockLines =
        stockLocationLineRepo
            .all()
            .filter(
                "self.product = ?1 AND self.stockLocation = ?2",
                fill.getProduct(),
                fill.getStockLocation())
            .fetch();

    // Sum up the current physical quantities
    for (StockLocationLine sl : stockLines) {
      if (sl.getCurrentQty() != null) {
        availableQty = availableQty.add(sl.getCurrentQty());
      }
    }

    // Compare available vs requested
    if (availableQty.compareTo(fill.getQty()) < 0) {
      throw new IllegalArgumentException(
          "Insufficient stock in "
              + fill.getStockLocation().getName()
              + ". Available: "
              + availableQty
              + ", Requested: "
              + fill.getQty());
    }
    // ---------------------------------------

    StockMove stockMove = new StockMove();

    stockMove.setCompany(fill.getStockLocation().getCompany());
    stockMove.setTypeSelect(StockMoveRepository.TYPE_INTERNAL); // 3 = Internal
    stockMove.setStatusSelect(StockMoveRepository.STATUS_PLANNED);
    stockMove.setEstimatedDate(LocalDate.now());
    stockMove.setFromStockLocation(fill.getStockLocation());

    StockLocation virtualFuelLocation =
        stockLocationRepo
            .all()
            .filter("self.name = ?1 AND self.typeSelect = ?2", "Fuel Consumptions Virtual", 3)
            .fetchOne();

    if (virtualFuelLocation == null) {
      throw new RuntimeException(
          "Configuration missing! Create a Stock Location named 'Fuel Consumptions Virtual' with type set to 'virtual'.");
    }
    stockMove.setToStockLocation(virtualFuelLocation);

    StockMoveLine line = new StockMoveLine();
    line.setProduct(fill.getProduct());
    line.setProductName(fill.getProduct().getName());

    line.setQty(fill.getQty());
    line.setRealQty(fill.getQty());

    line.setFromStockLocation(stockMove.getFromStockLocation());
    line.setToStockLocation(stockMove.getToStockLocation());

    if (fill.getProduct().getUnit() != null) {
      line.setUnit(fill.getProduct().getUnit());
    } else {
      throw new RuntimeException(
          "Product '" + fill.getProduct().getName() + "' does not have a Unit of Measure defined!");
    }

    line.setStockMove(stockMove);
    stockMove.addStockMoveLineListItem(line);

    stockMove = stockMoveRepo.save(stockMove);

    fill.setStockMove(stockMove);
    fuelFillRepo.save(fill);

    stockMoveService.realize(stockMove);
  }
}
