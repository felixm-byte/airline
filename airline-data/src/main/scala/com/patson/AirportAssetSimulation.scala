package com.patson

import com.patson.data.{AirlineSource, AirportAssetSource, DelegateSource}
import com.patson.model._

import scala.collection.mutable.ListBuffer
import scala.math.BigDecimal.RoundingMode
import scala.collection.mutable

object AirportAssetSimulation {

  def computePaxStats(airportIds : Set[Int], linkRidershipDetails : Map[(PassengerGroup, Airport, Route), Int]) : Map[Int, PassengerStats] = {

    val arrivalGroupsByDestAirportIds = mutable.Map[Int, ListBuffer[(PassengerGroup, Int)]]() //key is arrival airport ID
    val transitGroupsByAirportIds =  mutable.Map[Int, ListBuffer[(PassengerGroup, Int)]]() //key is transit airport ID

    //init with keys that we care
    airportIds.foreach { airportId =>
      arrivalGroupsByDestAirportIds.put(airportId, ListBuffer())
      transitGroupsByAirportIds.put(airportId, ListBuffer())
    }

    linkRidershipDetails.foreach {
      case((group, toAirport, route), passengerCount) =>
        arrivalGroupsByDestAirportIds.get(toAirport.id) match {
          case Some(groups) => groups.append((group, passengerCount))
          case None => //airports that we don't care (no assets), let's not build the list
        }

        for (i <- 0 until route.links.size) {
          if (i > 0) {
            val airportId = route.links(i).from.id
            transitGroupsByAirportIds.get(airportId) match {
              case Some(groups) => groups.append((group, passengerCount))
              case None => //airports that we don't care (no assets), let's not build the list
            }
          }
        }
    }

    airportIds.map { airportId =>
      val stats = PassengerStats(
        transitGroupsByAirportIds(airportId).map(_._2).sum,
        arrivalGroupsByDestAirportIds(airportId).filter(_._1.passengerType != PassengerType.BUSINESS).map(_._2).sum,
        arrivalGroupsByDestAirportIds(airportId).filter(_._1.passengerType == PassengerType.BUSINESS).map(_._2).sum
      )
      (airportId, stats)
    }.toMap
  }

  def simulate(currentCycle : Int, linkRidershipDetails : Map[(PassengerGroup, Airport, Route), Int]) = {

    val allAssets = AirportAssetSource.loadAirportAssetsByAssetCriteria(List.empty)
    val allAssetPropertiesHistory = ListBuffer[AirportAssetPropertiesHistory]()

    val allAirportIds = allAssets.map(_.airport.id).toSet
    val paxStatsByAirportId = computePaxStats(allAirportIds, linkRidershipDetails)


    allAssets.foreach { asset =>
      //check for changes due to upgrade
      checkUpgradeCompletion(asset) match {
        case Some((newBoosts, newRoi, upgradeFactor)) =>
          println(s"$asset upgrading to $newBoosts")
          asset.boosts = newBoosts.map(_._1)
          //save the boost history
          val newBoostHistory = newBoosts.map {
            case (boost, gain) => AirportAssetBoostHistory(asset.id, asset.level, boost.boostType, boost.value, gain, upgradeFactor, currentCycle)
          }
          AirportAssetSource.saveAirportBoostHistory(newBoostHistory)

          asset.roi = newRoi
          asset.upgradeApplied = true
        case None => //do nothing
      }

      val result = simulateAssetBusiness(asset, paxStatsByAirportId(asset.airport.id))
      asset.revenue = result.revenue
      asset.expense = result.expense
      asset.properties = asset.properties ++ result.properties

      AirportAssetSource.updateAirportAsset(asset)

      allAssetPropertiesHistory.append(AirportAssetPropertiesHistory(asset.id, asset.properties + ("revenue" -> result.revenue) + ("expense" -> result.expense), currentCycle))

      //TODO update airline finances (history/cash balance) or should this be done in Airline Sim?
    }

    AirportAssetSource.deleteAirportPropertiesHistory(currentCycle - 100)
    AirportAssetSource.saveAirportPropertiesHistory(allAssetPropertiesHistory.toList)

  }

  /**
    * Check whether there should be new boosts
    *
    * @return Some if there are new boosts. 2nd value in list tuple is boost gain
    */
  def checkUpgradeCompletion(asset : AirportAsset) : Option[(List[(AirportBoost, Double)], Double, Double)] = { //(new boost, new roi, upgrade factor)
    if (asset.status == AirportAssetStatus.COMPLETED && !asset.upgradeApplied) {
      val history = asset.boostHistory()
      if (history.isEmpty || history.map(_.level).max < asset.level) { //double check, the upgradeApplied flag is actually good enough
        val previousLevelBoosts =
          if (history.isEmpty) { //use basic as starting point
            asset.blueprint.assetType.baseBoosts
          } else {
            asset.boosts
          }
        val upgradeFactor = generateUpgradeFactor(asset)
        Some(computeNewBoosts(asset, previousLevelBoosts, upgradeFactor), computeNewRoi(asset, upgradeFactor), upgradeFactor)
      } else {
        None
      }
    } else {
      None
    }
  }

  /**
    * how successful the upgrade is, from 0 to 1
    */
  def generateUpgradeFactor(asset : AirportAsset) : Double = {
    //get performance factor up to last 10 weeks
    val historyEntries = AirportAssetSource.loadAirportPropertyHistoryByAssetId(asset.id).sortBy(_.cycle).takeRight(10)
    val performances = historyEntries.map { entry =>
      val profit = entry.properties("revenue") - entry.properties("expense")
      val actualRoi = profit * 52.0 / asset.value
      val potentialRoi = asset.roi
      val performance = Math.max(0, Math.min(1.0, actualRoi / potentialRoi))
      performance
    }
    if (performances.length == 0) {
      Math.random()
    } else {
      val finalPerformance =  performances.sum / performances.length //from 0 to 1 . if no previous history (new asset) use 0.5
      Math.random() * 0.5 + finalPerformance * 0.5 //50% performance 50% luck
    }


  }

  def computeNewBoosts(asset : AirportAsset, previousLevelBoosts : List[AirportBoost], upgradeFactor : Double) : List[(AirportBoost, Double)] = { //2nd value is gain
    previousLevelBoosts.map { previousBoost =>
      val maxGain = asset.assetType.baseBoosts.find(_.boostType == previousBoost.boostType).get.value * 0.2
      var gain = maxGain * (0.2 + upgradeFactor * 0.8)
      if (AirportBoostType.getValueType(previousBoost.boostType) == classOf[Long]) {
        gain = gain.toLong
      } else {
        gain = BigDecimal(gain).setScale(2, RoundingMode.HALF_UP).toDouble
      }
      var newValue = previousBoost.value + gain
      if (AirportBoostType.getValueType(previousBoost.boostType) == classOf[Long]) {
        newValue = newValue.toLong
      }
      (AirportBoost(previousBoost.boostType, newValue), gain)
    }
  }

  def computeNewRoi(asset : AirportAsset, upgradeFactor : Double) = {
    val maxGrowth = (asset.assetType.maxRoi - asset.assetType.initRoi) / AirportAsset.MAX_LEVEL
    val delta = (upgradeFactor - 0.3) / (1 - 0.3) * maxGrowth // < 0.3 -> decrease, at 1 -> max Growth
    asset.roi + delta
  }

  /**
    *
    * @param asset
    */
  def simulateAssetBusiness(asset : AirportAsset, paxStats : PassengerStats) : AssetSimulationResult = {
    import com.patson.model.AirportAssetType._
    if (asset.level == 1 && asset.status != AirportAssetStatus.COMPLETED) {
      AssetSimulationResult(0, 0, Map.empty)
    } else {
      val result : AssetSimulationResult = asset.assetType match {
        case CITY_TRANSIT => ???
        case AIRPORT_HOTEL | GRAND_HOTEL_TOURIST | GRAND_HOTEL_BUSINESS | BEACH_RESORT | SKI_RESORT | INN | HOTEL | LUXURIOUS_HOTEL =>
          simulateHotelAssetPerformance(asset.asInstanceOf[HotelAsset], paxStats)
        case AMUSEMENT_PARK | STADIUM | MUSEUM | LANDMARK | SPORT_ARENA | CINEMA | GOLF_COURSE => ???
          simulateAdmissionAssetPerformance(asset.asInstanceOf[AdmissionAsset], paxStats)
        case SUBWAY => ???
        case CONVENTION_CENTER => ???
        case SCIENCE_PARK => ???
        case SOLAR_POWER_PLANT => ???
        case TRAVEL_AGENCY => ???
        case GAME_ARCADE => ???
        case OFFICE_BUILDING_1 => ???
        case OFFICE_BUILDING_2 => ???
        case RESTAURANT => ???
        case OFFICE_BUILDING_3 => ???
        case SHOPPING_MALL => ???
        case OFFICE_BUILDING_4 => ???
        case RESIDENTIAL_COMPLEX => ???
        case _ =>
          println(s"Missing business sim for ${asset.assetType}")
          AssetSimulationResult(0, 0, Map.empty)
      }
      result
    }
  }

  case class PassengerStats(transferPax : Long, arrivalTourist : Long, arrivalBusiness : Long) {
    val arrivalPax = arrivalTourist + arrivalBusiness
  }

  def simulateHotelAssetPerformance(asset : HotelAsset, paxStats : PassengerStats): AssetSimulationResult = {
    var potentialGuests : Int = asset.assetType match {
      case com.patson.model.AirportAssetType.AIRPORT_HOTEL =>
      //assume 30% transfer pax will use airport hotel, 5% arrival will use this
        (paxStats.transferPax * 0.3 + paxStats.arrivalPax * 0.05).toInt
      case com.patson.model.AirportAssetType.GRAND_HOTEL_TOURIST =>
        (paxStats.arrivalTourist * 0.35).toInt
      case com.patson.model.AirportAssetType.GRAND_HOTEL_BUSINESS =>
        (paxStats.arrivalBusiness * 0.3).toInt + (paxStats.arrivalBusiness * 0.05).toInt
      case com.patson.model.AirportAssetType.BEACH_RESORT =>
        (paxStats.arrivalTourist * 0.5).toInt
      case com.patson.model.AirportAssetType.SKI_RESORT =>
        (paxStats.arrivalTourist * 0.5).toInt
      case com.patson.model.AirportAssetType.INN =>
        (paxStats.arrivalPax * 0.2).toInt
      case com.patson.model.AirportAssetType.HOTEL =>
        (paxStats.arrivalPax * 0.2).toInt
      case com.patson.model.AirportAssetType.LUXURIOUS_HOTEL =>
        (paxStats.arrivalTourist * 0.1 + paxStats.arrivalBusiness * 0.2).toInt
      case _ => println(s"Unknown hotel type for performance computation!! ${asset.assetType}")
        0
    }


    potentialGuests = (potentialGuests * Util.getBellRandom(1)).toInt

    val potentialToCapRatio = potentialGuests.toDouble / asset.capacity
    //potentialGuests has to be 10 times of capacity for 100% performance, otherwise at 50% for full capacity
    val performanceFactor = if (potentialToCapRatio < 1) 0.5 * potentialToCapRatio else 0.5 + 0.5 * Math.min(1, potentialToCapRatio / 10)

    val neutralProfitFactor = 0.25 //start losing money < 0.25 performance

    val weeklyProfit = asset.value * asset.roi / 52 * (performanceFactor - neutralProfitFactor) / (1 - neutralProfitFactor)
    //from profit, deduce expense by considering revenue = 0 at performanceFactor = 0.
    val baseExpense = asset.value * asset.roi / 52 * (0 - neutralProfitFactor) / (1 - neutralProfitFactor) * -1

    val occupancy = Math.min(asset.capacity, potentialGuests)
    //expense increase slightly per occupancy
    val costPerGuestPerNight = asset.assetType match {
      case com.patson.model.AirportAssetType.AIRPORT_HOTEL => 25
      case com.patson.model.AirportAssetType.GRAND_HOTEL_TOURIST => 100
      case com.patson.model.AirportAssetType.GRAND_HOTEL_BUSINESS => 100
      case com.patson.model.AirportAssetType.BEACH_RESORT => 50
      case com.patson.model.AirportAssetType.SKI_RESORT => 50
      case com.patson.model.AirportAssetType.INN => 35
      case com.patson.model.AirportAssetType.HOTEL => 40
      case com.patson.model.AirportAssetType.LUXURIOUS_HOTEL => 150
      case _ =>
        println(s"Unknown hotel type for costPerGuest!! ${asset.assetType}")
        0
    }


    //finally from revenue deduce room rate
    //expense increase slightly per occupancy
    val nightsPerGuest = asset.assetType match {
      case com.patson.model.AirportAssetType.AIRPORT_HOTEL => 2
      case com.patson.model.AirportAssetType.GRAND_HOTEL_TOURIST => 5
      case com.patson.model.AirportAssetType.GRAND_HOTEL_BUSINESS => 3
      case com.patson.model.AirportAssetType.BEACH_RESORT => 7
      case com.patson.model.AirportAssetType.SKI_RESORT => 5
      case com.patson.model.AirportAssetType.INN => 3
      case com.patson.model.AirportAssetType.HOTEL => 5
      case com.patson.model.AirportAssetType.LUXURIOUS_HOTEL => 5
      case _ =>
        println(s"Unknown hotel type for costPerGuest!! ${asset.assetType}")
        1
    }

    val costPerGuest = costPerGuestPerNight * nightsPerGuest
    val expense = baseExpense + occupancy * costPerGuest
    val revenue = expense + weeklyProfit

    val roomRate =
      if (revenue > 0) {
        (revenue / occupancy / nightsPerGuest * 0.8).toInt //*0.8, assume 20% income from something else
      } else {
        (costPerGuest * 1.5).toInt
      }

    val properties : Map[String, Long] = Map("occupancy" -> occupancy, "rate" -> roomRate)
    AssetSimulationResult(revenue.toLong, expense.toLong, properties)
  }

  def simulateAdmissionAssetPerformance(asset : AdmissionAsset, paxStats : PassengerStats): AssetSimulationResult = {
    var potentialGuests = asset.assetType match {
      case com.patson.model.AirportAssetType.AMUSEMENT_PARK =>
        (paxStats.arrivalTourist * 0.4 +
          (if (asset.airport.incomeLevel >= 30) {
            1
          } else {
            asset.airport.incomeLevel / 50
          }) * asset.airport.population / 52 / 2).toInt //assuming income level 30, local pop visit it once every 2 years
      case com.patson.model.AirportAssetType.STADIUM =>
        (paxStats.arrivalPax * 0.1 +
          (if (asset.airport.incomeLevel >= 20) {
            1
          } else {
            asset.airport.incomeLevel / 25
          }) * asset.airport.population / 52).toInt
      case com.patson.model.AirportAssetType.MUSEUM =>
        (paxStats.arrivalPax * 0.2 +
          (if (asset.airport.incomeLevel >= 40) {
            1
          } else {
            asset.airport.incomeLevel / 80
          }) * asset.airport.population / 52 / 1.5).toInt
      case com.patson.model.AirportAssetType.LANDMARK =>
        (paxStats.arrivalTourist * 0.5 + paxStats.arrivalTourist * 0.2 +
          (if (asset.airport.incomeLevel >= 25) {
            1
          } else {
            asset.airport.incomeLevel / 30
          }) * asset.airport.population / 52 / 10).toInt
      case com.patson.model.AirportAssetType.SPORT_ARENA =>
        (paxStats.arrivalPax * 0.15 +
          (if (asset.airport.incomeLevel >= 25) {
            1
          } else {
            asset.airport.incomeLevel / 30
          }) * asset.airport.population / 52 * 4).toInt
      case com.patson.model.AirportAssetType.CINEMA =>
        (paxStats.arrivalPax * 0.15 +
          (if (asset.airport.incomeLevel >= 20) {
            1
          } else {
            asset.airport.incomeLevel / 25
          }) * asset.airport.population / 52 * 12).toInt
      case com.patson.model.AirportAssetType.GOLF_COURSE =>
        (paxStats.arrivalPax * 0.03 +
          (if (asset.airport.incomeLevel >= 45) {
            1
          } else {
            asset.airport.incomeLevel / 120
          }) * asset.airport.population / 52 / 100).toInt
      case _ =>
        println(s"Unknown admission type ${asset.assetType}")
        0
    }

    potentialGuests = (potentialGuests * Util.getBellRandom(1, 0.2)).toInt

    val potentialToCapRatio = potentialGuests.toDouble / asset.capacity
    //potentialGuests has to be 5 times of capacity for 100% performance, otherwise at 70% for full capacity
    val performanceFactor = if (potentialToCapRatio < 1) 0.7 * potentialToCapRatio else 0.7 + 0.3 * Math.min(1, potentialToCapRatio / 5)

    val neutralProfitFactor = 0.6 //start losing money < 0.6 performance

    val weeklyProfit = asset.value * asset.roi / 52 * (performanceFactor - neutralProfitFactor) / (1 - neutralProfitFactor)
    val expense = asset.value * asset.roi / 52 * (0 - neutralProfitFactor) / (1 - neutralProfitFactor) * -1
    val visitorsApprox = Math.min(potentialGuests, asset.capacity)
    val revenue = expense + weeklyProfit

    //a little bit different here, use ticket price step
    val ticketStep = asset.assetType match {
      case com.patson.model.AirportAssetType.AMUSEMENT_PARK =>
        10
      case com.patson.model.AirportAssetType.STADIUM =>
        10
      case com.patson.model.AirportAssetType.MUSEUM =>
        5
      case com.patson.model.AirportAssetType.LANDMARK =>
        5
      case com.patson.model.AirportAssetType.SPORT_ARENA =>
        5
      case com.patson.model.AirportAssetType.CINEMA =>
        2
      case com.patson.model.AirportAssetType.GOLF_COURSE =>
        20
      case _ =>
        println(s"Unknown admission type ${asset.assetType}")
        1
    }
    var ticketPriceApprox =
      if (visitorsApprox == 0) {
        0
      } else {
        revenue.toDouble / visitorsApprox
      }
    val ticketPrice = ((ticketPriceApprox / ticketStep).toInt + 1) * ticketStep
    val visitors = (revenue / ticketPrice).toInt //adjust visitors


    val properties : Map[String, Long] = Map("visitors" -> visitors, "rate" -> ticketPrice)
    AssetSimulationResult(revenue.toLong, expense.toLong, properties)
  }

  case class AssetSimulationResult(revenue : Long, expense : Long, properties : Map[String, Long])
}
