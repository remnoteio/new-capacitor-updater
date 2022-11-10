//
//  Future.swift
//  Plugin
//
//  Created by Jesse Onolememen on 10/11/2022.
//  Copyright © 2022 Capgo. All rights reserved.
//

import Foundation

public class Future<Value> {
    typealias Result = Swift.Result<Value, Error>
    
    var result: Result? {
        // Observe whenever a result is assigned, and report it:
        didSet { result.map(report) }
    }
    private var callbacks = [(Result) -> Void]()
    
    func observe(using callback: @escaping (Result) -> Void) {
        // If a result has already been set, call the callback directly:
        if let result = result {
            return callback(result)
        }
        
        callbacks.append(callback)
    }
    
    private func report(result: Result) {
        callbacks.forEach { $0(result) }
        callbacks = []
    }
}
