package com.easybc.planner

import android.app.Application
import com.easybc.planner.bridge.PlannerBridge
import com.easybc.planner.bridge.createPlannerBridge
import com.easybc.planner.data.db.AppDatabase
import com.easybc.planner.data.PlannerRepository
import com.easybc.planner.util.CycleCalculator

class EasyBCApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    val bridge: PlannerBridge by lazy { createPlannerBridge() }

    val cycleCalculator: CycleCalculator by lazy { CycleCalculator() }

    val repository: PlannerRepository by lazy {
        PlannerRepository(database, bridge, cycleCalculator)
    }
}
