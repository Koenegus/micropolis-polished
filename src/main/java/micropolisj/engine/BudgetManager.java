// This file is part of MicropolisJ.
// Copyright (C) 2013 Jason Long
// Portions Copyright (C) 1989-2007 Electronic Arts Inc.
//
// MicropolisJ is free software; you can redistribute it and/or modify
// it under the terms of the GNU GPLv3, with additional terms.
// See the README file, included in this distribution, for details.

package micropolisj.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the city's finances, tax collection, service funding percentages, maintenance effects,
 * and financial history.
 *
 * <p>Extracted from {@link Micropolis} to adhere to the Single Responsibility Principle.
 */
public class BudgetManager {

  public static final int TAXFREQ = 48;

  /** Road/rail maintenance cost multiplier, for various difficulty settings. */
  private static final double[] R_LEVELS = {0.7, 0.9, 1.2};

  /** Tax income multiplier, for various difficulty settings. */
  private static final double[] F_LEVELS = {1.4, 1.2, 0.8};

  /** Annual maintenance cost of each police station. */
  private static final int POLICE_STATION_MAINTENANCE = 100;

  /** Annual maintenance cost of each fire station. */
  private static final int FIRE_STATION_MAINTENANCE = 100;

  private final Micropolis city;
  private final CityBudget budget = new CityBudget();
  private final List<FinancialHistory> financialHistory = new ArrayList<>();

  private boolean autoBudget;
  private int cityTax = 7;
  private double roadPercent = 1.0;
  private double policePercent = 1.0;
  private double firePercent = 1.0;
  private int roadEffect = 32;
  private int policeEffect = 1000;
  private int fireEffect = 1000;
  private int taxEffect = 7;
  private int cashFlow;

  // used in generateBudget()
  private int lastRoadTotal;
  private int lastRailTotal;
  private int lastTotalPop;
  private int lastFireStationCount;
  private int lastPoliceCount;

  public BudgetManager(Micropolis city) {
    if (city == null) {
      throw new IllegalArgumentException("city cannot be null");
    }
    this.city = city;
  }

  public void spend(int amount) {
    budget.setTotalFunds(budget.getTotalFunds() - amount);
    city.fireFundsChanged();
  }

  public void setFunds(int totalFunds) {
    budget.setTotalFunds(totalFunds);
  }

  /** Checks whether the next call to animate() will collect taxes and process the budget. */
  public boolean isBudgetTime(int cityTime, int fcycle, int acycle) {
    return cityTime != 0
        && cityTime % TAXFREQ == 0
        && (fcycle + 1) % 16 == 10
        && (acycle + 1) % 2 == 0;
  }

  /** Accumulates partial tax income and maintenance escrows during a simulation cycle. */
  public void collectTaxPartial() {
    lastRoadTotal = city.getRoadTotal();
    lastRailTotal = city.getRailTotal();
    lastTotalPop = city.getTotalPop();
    lastFireStationCount = city.getFireStationCount();
    lastPoliceCount = city.getPoliceCount();

    BudgetNumbers b = generateBudget();

    budget.setTaxFund(budget.getTaxFund() + b.getTaxIncome());
    budget.setRoadFundEscrow(budget.getRoadFundEscrow() - b.getRoadFunded());
    budget.setFireFundEscrow(budget.getFireFundEscrow() - b.getFireFunded());
    budget.setPoliceFundEscrow(budget.getPoliceFundEscrow() - b.getPoliceFunded());

    taxEffect = b.getTaxRate();
    roadEffect =
        b.getRoadRequest() != 0
            ? (int) Math.floor(32.0 * b.getRoadFunded() / b.getRoadRequest())
            : 32;
    policeEffect =
        b.getPoliceRequest() != 0
            ? (int) Math.floor(1000.0 * b.getPoliceFunded() / b.getPoliceRequest())
            : 1000;
    fireEffect =
        b.getFireRequest() != 0
            ? (int) Math.floor(1000.0 * b.getFireFunded() / b.getFireRequest())
            : 1000;
  }

  /** Settles annual taxes and operating expenses and records a financial history entry. */
  public void collectTax() {
    int revenue = budget.getTaxFund() / TAXFREQ;
    int expenses =
        -(budget.getRoadFundEscrow() + budget.getFireFundEscrow() + budget.getPoliceFundEscrow())
            / TAXFREQ;

    FinancialHistory hist = new FinancialHistory();
    hist.setCityTime(city.getCityTime());
    hist.setTaxIncome(revenue);
    hist.setOperatingExpenses(expenses);

    cashFlow = revenue - expenses;
    spend(-cashFlow);

    hist.setTotalFunds(budget.getTotalFunds());
    financialHistory.add(0, hist);

    budget.setTaxFund(0);
    budget.setRoadFundEscrow(0);
    budget.setFireFundEscrow(0);
    budget.setPoliceFundEscrow(0);
  }

  /** Calculates the current budget numbers based on population, land value, and infrastructure. */
  public BudgetNumbers generateBudget() {
    BudgetNumbers b = new BudgetNumbers();
    b.setTaxRate(Math.max(0, cityTax));
    b.setRoadPercent(Math.max(0.0, roadPercent));
    b.setFirePercent(Math.max(0.0, firePercent));
    b.setPolicePercent(Math.max(0.0, policePercent));

    int gameLevel = city.getGameLevel();
    b.setTaxIncome(
        (int)
            Math.round(
                lastTotalPop
                    * city.getLandValueAverage()
                    / 120.0
                    * b.getTaxRate()
                    * F_LEVELS[gameLevel]));
    assert b.getTaxIncome() >= 0;

    b.setRoadRequest((int) Math.round((lastRoadTotal + lastRailTotal * 2) * R_LEVELS[gameLevel]));
    b.setFireRequest(FIRE_STATION_MAINTENANCE * lastFireStationCount);
    b.setPoliceRequest(POLICE_STATION_MAINTENANCE * lastPoliceCount);

    b.setRoadFunded((int) Math.round(b.getRoadRequest() * b.getRoadPercent()));
    b.setFireFunded((int) Math.round(b.getFireRequest() * b.getFirePercent()));
    b.setPoliceFunded((int) Math.round(b.getPoliceRequest() * b.getPolicePercent()));

    int yumDuckets = budget.getTotalFunds() + b.getTaxIncome();
    assert yumDuckets >= 0;

    if (yumDuckets >= b.getRoadFunded()) {
      yumDuckets -= b.getRoadFunded();
      if (yumDuckets >= b.getFireFunded()) {
        yumDuckets -= b.getFireFunded();
        if (yumDuckets < b.getPoliceFunded()) {
          assert b.getPoliceRequest() != 0;

          b.setPoliceFunded(yumDuckets);
          b.setPolicePercent((double) b.getPoliceFunded() / b.getPoliceRequest());
        }
      } else {
        assert b.getFireRequest() != 0;

        b.setFireFunded(yumDuckets);
        b.setFirePercent((double) b.getFireFunded() / b.getFireRequest());
        b.setPoliceFunded(0);
        b.setPolicePercent(0.0);
      }
    } else {
      assert b.getRoadRequest() != 0;

      b.setRoadFunded(yumDuckets);
      b.setRoadPercent((double) b.getRoadFunded() / b.getRoadRequest());
      b.setFireFunded(0);
      b.setFirePercent(0.0);
      b.setPoliceFunded(0);
      b.setPolicePercent(0.0);
    }

    return b;
  }

  public void toggleAutoBudget() {
    autoBudget = !autoBudget;
    city.fireOptionsChanged();
  }

  public boolean isAutoBudget() {
    return autoBudget;
  }

  public void setAutoBudget(boolean autoBudget) {
    this.autoBudget = autoBudget;
    city.fireOptionsChanged();
  }

  public CityBudget getBudget() {
    return budget;
  }

  public List<FinancialHistory> getFinancialHistory() {
    return financialHistory;
  }

  public int getCityTax() {
    return cityTax;
  }

  public void setCityTax(int cityTax) {
    this.cityTax = cityTax;
  }

  public double getRoadPercent() {
    return roadPercent;
  }

  public void setRoadPercent(double roadPercent) {
    this.roadPercent = roadPercent;
  }

  public double getPolicePercent() {
    return policePercent;
  }

  public void setPolicePercent(double policePercent) {
    this.policePercent = policePercent;
  }

  public double getFirePercent() {
    return firePercent;
  }

  public void setFirePercent(double firePercent) {
    this.firePercent = firePercent;
  }

  public int getRoadEffect() {
    return roadEffect;
  }

  public int getPoliceEffect() {
    return policeEffect;
  }

  public int getFireEffect() {
    return fireEffect;
  }

  public int getTaxEffect() {
    return taxEffect;
  }

  public void setTaxEffect(int taxEffect) {
    this.taxEffect = taxEffect;
  }

  public int getCashFlow() {
    return cashFlow;
  }
}
